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
import com.google.gerrit.server.events.EventListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import com.googlesource.gerrit.plugins.reviewai.listener.GerritListener;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class ReviewAiLifecycle implements LifecycleListener {
  private final ReviewAiExecutors reviewAiExecutors;
  private final DynamicSet<EventListener> eventListeners;
  private final String pluginName;
  private final String listenerClassName;

  @Inject
  ReviewAiLifecycle(
      ReviewAiExecutors reviewAiExecutors,
      DynamicSet<EventListener> eventListeners,
      @PluginName String pluginName) {
    this(reviewAiExecutors, eventListeners, pluginName, GerritListener.class.getName());
  }

  ReviewAiLifecycle(
      ReviewAiExecutors reviewAiExecutors,
      DynamicSet<EventListener> eventListeners,
      String pluginName,
      String listenerClassName) {
    this.reviewAiExecutors = reviewAiExecutors;
    this.eventListeners = eventListeners;
    this.pluginName = pluginName;
    this.listenerClassName = listenerClassName;
  }

  @Override
  public void start() {
    log.info("Starting ReviewAI lifecycle");
    List<String> existingReviewAiListeners = findExistingReviewAiListeners();
    if (!existingReviewAiListeners.isEmpty()) {
      throw new IllegalStateException(
          String.format(
              "ReviewAI plugin load refused: found %d already registered %s listener(s) for"
                  + " plugin '%s'. This usually means Gerrit did not unload the previous plugin"
                  + " instance before installing a new one. Existing listeners: %s",
              existingReviewAiListeners.size(),
              listenerClassName,
              pluginName,
              existingReviewAiListeners));
    }
  }

  @Override
  public void stop() {
    log.info("Stopping ReviewAI lifecycle");
    reviewAiExecutors.shutdown();
    ReviewAiDb.stopManagedTcpServer();
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
        throw new IllegalStateException(
            String.format(
                "ReviewAI plugin load refused: unable to inspect an already registered event"
                    + " listener for plugin '%s'. This usually means Gerrit kept a stale listener"
                    + " registration while reloading the plugin.",
                pluginName),
            e);
      }
      if (listenerClassName.equals(listener.getClass().getName())) {
        listeners.add(describeListener(listener));
      }
    }
    return listeners;
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
