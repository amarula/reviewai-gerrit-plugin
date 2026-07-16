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
import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.server.events.EventListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.listener.GerritListener;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class ReviewAiLifecycle implements LifecycleListener {
  private final DynamicSet<EventListener> eventListeners;
  private final GerritListener gerritListener;
  private final String pluginName;
  private RegistrationHandle registrationHandle;

  @Inject
  ReviewAiLifecycle(
      DynamicSet<EventListener> eventListeners,
      GerritListener gerritListener,
      @PluginName String pluginName) {
    this.eventListeners = eventListeners;
    this.gerritListener = gerritListener;
    this.pluginName = pluginName;
  }

  @Override
  public synchronized void start() {
    if (registrationHandle != null) {
      log.warn("ReviewAI event listener is already registered");
      return;
    }
    registrationHandle = eventListeners.add(pluginName, gerritListener);
    log.info("Registered ReviewAI event listener");
  }

  @Override
  public synchronized void stop() {
    if (registrationHandle == null) {
      log.info("ReviewAI event listener is not registered");
      return;
    }
    registrationHandle.remove();
    registrationHandle = null;
    log.info("Unregistered ReviewAI event listener");
  }
}
