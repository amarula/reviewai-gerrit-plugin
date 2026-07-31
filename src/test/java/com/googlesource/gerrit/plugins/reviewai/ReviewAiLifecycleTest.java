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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import org.junit.Test;
import org.mockito.MockedStatic;

public class ReviewAiLifecycleTest {
  private static final String PLUGIN_NAME = "reviewai-gerrit-plugin";

  @Test
  public void startPassesWhenNoExistingListenerIsRegistered() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();

    newLifecycle(eventListeners).start();
  }

  @Test
  public void startPassesWhenMatchingListenerBelongsToAnotherPlugin() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    eventListeners.add("other-plugin", new TargetEventListener());

    newLifecycle(eventListeners).start();
  }

  @Test
  public void startPassesWhenSamePluginHasAnotherListenerType() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    eventListeners.add(PLUGIN_NAME, new OtherEventListener());

    newLifecycle(eventListeners).start();
  }

  @Test
  public void startFailsWhenSamePluginAlreadyHasTargetListenerRegistered() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    eventListeners.add(PLUGIN_NAME, new TargetEventListener());

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> newLifecycle(eventListeners).start());

    assertTrue(exception.getMessage().contains("ReviewAI plugin load refused"));
    assertTrue(exception.getMessage().contains("found 1 already registered"));
    assertTrue(exception.getMessage().contains(PLUGIN_NAME));
    assertTrue(exception.getMessage().contains(TargetEventListener.class.getName()));
  }

  @Test
  public void startFailsWithSpecificMessageWhenSamePluginListenerCannotBeInspected() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    Provider<EventListener> throwingProvider =
        () -> {
          throw new IllegalStateException("broken listener provider");
        };
    eventListeners.add(PLUGIN_NAME, throwingProvider);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> newLifecycle(eventListeners).start());

    assertTrue(exception.getMessage().contains("ReviewAI plugin load refused"));
    assertTrue(exception.getMessage().contains("unable to inspect"));
    assertTrue(exception.getMessage().contains(PLUGIN_NAME));
    assertTrue(exception.getCause().getMessage().contains("broken listener provider"));
  }

  @Test
  public void stopShutsDownExecutors() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    ReviewAiExecutors executors = mock(ReviewAiExecutors.class);

    ReviewAiLifecycle lifecycle = newLifecycle(eventListeners, executors);
    lifecycle.stop();

    verify(executors).shutdown();
  }

  @Test
  public void stopStopsManagedTcpServer() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();

    try (MockedStatic<ReviewAiDb> mockedDb = mockStatic(ReviewAiDb.class)) {
      ReviewAiLifecycle lifecycle = newLifecycle(eventListeners);
      lifecycle.stop();

      mockedDb.verify(ReviewAiDb::stopManagedTcpServer);
    }
  }

  @Test
  public void fullRestartSucceedsWhenOldListenerIsProperlyRemoved() {
    // Simulates the correct Gerrit plugin reload sequence:
    // 1. Old plugin instance registers its listener
    // 2. Gerrit properly unloads the old plugin (removes its entries from DynamicSet)
    // 3. New plugin instance starts successfully because old listeners were cleaned up

    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    RegistrationHandle handle =
        eventListeners.add(PLUGIN_NAME, new TargetEventListener());

    // Verify the listener is registered
    assertTrue(
        "Sanity check: listener should be registered before removal",
        eventListeners.entries().iterator().hasNext());

    // Gerrit properly cleans up: remove the old plugin's listener
    handle.remove();

    // After proper cleanup, the new plugin start should succeed
    newLifecycle(eventListeners).start();
  }

  private static ReviewAiLifecycle newLifecycle(DynamicSet<EventListener> eventListeners) {
    return newLifecycle(eventListeners, mock(ReviewAiExecutors.class));
  }

  private static ReviewAiLifecycle newLifecycle(
      DynamicSet<EventListener> eventListeners, ReviewAiExecutors executors) {
    return new ReviewAiLifecycle(
        executors,
        eventListeners,
        PLUGIN_NAME,
        TargetEventListener.class.getName());
  }

  private static class TargetEventListener implements EventListener {
    @Override
    public void onEvent(Event event) {}
  }

  private static class OtherEventListener implements EventListener {
    @Override
    public void onEvent(Event event) {}
  }
}
