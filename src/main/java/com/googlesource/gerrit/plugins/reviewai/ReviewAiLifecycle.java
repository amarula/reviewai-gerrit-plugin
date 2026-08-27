/*
 * Copyright (c) 2026. The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlesource.gerrit.plugins.reviewai;

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.registration.Extension;
import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.server.events.EventListener;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernSanitizer;
import com.googlesource.gerrit.plugins.reviewai.listener.GerritListener;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages the ReviewAI plugin lifecycle.
 *
 * <p>Registers and unregisters the {@link GerritListener} event listener manually so the plugin
 * owns the {@link RegistrationHandle} and can remove it during {@link #stop()}. This avoids stale
 * listeners when Gerrit fails to clean up the previous plugin instance during reload.
 */
@Slf4j
@Singleton
public class ReviewAiLifecycle implements LifecycleListener {
  private final ReviewAiExecutors reviewAiExecutors;
  private final Provider<GerritListener> gerritListenerProvider;
  private final DynamicSet<EventListener> eventListeners;
  private final ReviewAiDb reviewAiDb;
  private final ReviewConcernSanitizer reviewConcernSanitizer;
  private final String pluginName;
  private final String listenerClassName;
  private RegistrationHandle listenerHandle;

  @Inject
  ReviewAiLifecycle(
      ReviewAiExecutors reviewAiExecutors,
      Provider<GerritListener> gerritListenerProvider,
      DynamicSet<EventListener> eventListeners,
      ReviewAiDb reviewAiDb,
      ReviewConcernSanitizer reviewConcernSanitizer,
      @PluginName String pluginName) {
    this(
        reviewAiExecutors,
        gerritListenerProvider,
        eventListeners,
        reviewAiDb,
        reviewConcernSanitizer,
        pluginName,
        GerritListener.class.getName());
  }

  ReviewAiLifecycle(
      ReviewAiExecutors reviewAiExecutors,
      Provider<GerritListener> gerritListenerProvider,
      DynamicSet<EventListener> eventListeners,
      ReviewAiDb reviewAiDb,
      ReviewConcernSanitizer reviewConcernSanitizer,
      String pluginName,
      String listenerClassName) {
    this.reviewAiExecutors = reviewAiExecutors;
    this.gerritListenerProvider = gerritListenerProvider;
    this.eventListeners = eventListeners;
    this.reviewAiDb = reviewAiDb;
    this.reviewConcernSanitizer = reviewConcernSanitizer;
    this.pluginName = pluginName;
    this.listenerClassName = listenerClassName;
  }

  @Override
  public void start() {
    log.info("Starting ReviewAI lifecycle");

    List<String> staleListeners = findExistingReviewAiListeners();
    if (!staleListeners.isEmpty()) {
      log.warn(
          "Found {} stale GerritListener(s) already registered for plugin '{}': {}. "
              + "Attempting proactive cleanup.",
          staleListeners.size(),
          pluginName,
          staleListeners);
      removeStaleListeners();
    }

    GerritListener listener = gerritListenerProvider.get();
    listenerHandle = eventListeners.add(pluginName, listener);
    log.info("Registered GerritListener for plugin '{}'", pluginName);

    reviewAiExecutors
        .getAgentExecutor()
        .execute(
            () -> {
              try {
                reviewConcernSanitizer.sanitize();
              } catch (Exception e) {
                log.error("Review concern sanitization failed", e);
              }
            });
  }

  @Override
  public void stop() {
    log.info("Stopping ReviewAI lifecycle");

    if (listenerHandle != null) {
      listenerHandle.remove();
      log.info("Unregistered GerritListener for plugin '{}'", pluginName);
      listenerHandle = null;
    }

    reviewAiExecutors.shutdown();
    reviewAiDb.stopManagedTcpServerIfOwner();
  }

  /**
   * Removes any {@code GerritListener} instances already registered for this plugin from the
   * {@link DynamicSet}.
   *
   * <p>On Gerrit 3.14.2, plugin reload and plugin add call {@code start()} on the new instance
   * before {@code stop()} on the old one, so stale entries may still be present. We match stale
   * listeners by plugin name + fully-qualified class name (not {@code instanceof}, which fails
   * across {@link ClassLoader} boundaries), then remove them by CAS'ing the underlying {@link
   * AtomicReference} to {@code null} via reflection.
   */
  private void removeStaleListeners() {
    Field itemsField = getItemsField();
    if (itemsField == null) {
      return;
    }

    @SuppressWarnings("unchecked")
    CopyOnWriteArrayList<AtomicReference<Extension<EventListener>>> items;
    try {
      items =
          (CopyOnWriteArrayList<AtomicReference<Extension<EventListener>>>)
              itemsField.get(eventListeners);
    } catch (IllegalAccessException e) {
      log.warn(
          "Unable to access DynamicSet internal state for plugin '{}': {}",
          pluginName,
          e.getMessage());
      return;
    }

    for (AtomicReference<Extension<EventListener>> ref : items) {
      Extension<EventListener> extension = ref.get();
      if (extension == null || !pluginName.equals(extension.getPluginName())) {
        continue;
      }
      EventListener listener;
      try {
        listener = extension.get();
      } catch (RuntimeException e) {
        log.warn(
            "Unable to inspect an event listener for plugin '{}': {}",
            pluginName,
            e.getMessage());
        continue;
      }
      if (listenerClassName.equals(listener.getClass().getName())) {
        if (ref.compareAndSet(extension, null)) {
          items.remove(ref);
          log.info(
              "Removed stale GerritListener for plugin '{}': {}",
              pluginName,
              describeListener(listener));
        }
      }
    }
  }

  private List<String> findExistingReviewAiListeners() {
    List<String> listeners = new ArrayList<>();
    for (Extension<EventListener> extension : eventListeners.entries()) {
      if (!pluginName.equals(extension.getPluginName())) {
        continue;
      }
      EventListener listener;
      try {
        listener = extension.get();
      } catch (RuntimeException e) {
        log.warn(
            "Unable to inspect an event listener for plugin '{}': {}",
            pluginName,
            e.getMessage());
        listeners.add(
            String.format(
                "%s(unreachable,classLoader@?)", listenerClassName));
        continue;
      }
      if (listenerClassName.equals(listener.getClass().getName())) {
        listeners.add(describeListener(listener));
      }
    }
    return listeners;
  }

  private Field getItemsField() {
    try {
      Field field = DynamicSet.class.getDeclaredField("items");
      field.setAccessible(true);
      return field;
    } catch (NoSuchFieldException e) {
      log.warn(
          "DynamicSet internals changed; cannot proactively remove stale listeners for"
              + " plugin '{}'. A Gerrit restart may be required if stale listeners persist.",
          pluginName);
      return null;
    }
  }

  private static String describeListener(EventListener listener) {
    Class<?> listenerClass = listener.getClass();
    ClassLoader classLoader = listenerClass.getClassLoader();
    return String.format(
        "%s@%x,classLoader@%x",
        listenerClass.getName(),
        System.identityHashCode(listener),
        classLoader == null ? 0 : System.identityHashCode(classLoader));
  }
}
