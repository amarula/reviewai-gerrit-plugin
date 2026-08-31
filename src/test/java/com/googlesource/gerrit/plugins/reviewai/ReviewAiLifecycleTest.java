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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.registration.Extension;
import com.google.gerrit.extensions.registration.RegistrationHandle;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.EventListener;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernSanitizer;
import com.googlesource.gerrit.plugins.reviewai.listener.EventHandlerExecutor;
import com.googlesource.gerrit.plugins.reviewai.listener.GerritListener;
import org.junit.Test;

public class ReviewAiLifecycleTest {
  private static final String PLUGIN_NAME = "reviewai-gerrit-plugin";

  private static final Provider<GerritListener> MOCK_LISTENER_PROVIDER =
      () -> mock(GerritListener.class);

  @Test
  public void startSucceedsWhenNoExistingListenerIsRegistered() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();

    newLifecycle(eventListeners).start();
  }

  @Test
  public void startSucceedsWhenMatchingListenerBelongsToAnotherPlugin() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    eventListeners.add("other-plugin", new TargetEventListener());

    newLifecycle(eventListeners).start();
  }

  @Test
  public void startSucceedsWhenSamePluginHasAnotherListenerType() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    eventListeners.add(PLUGIN_NAME, new OtherEventListener());

    newLifecycle(eventListeners).start();
  }

  @Test
  public void startProactivelyRemovesStaleListenerAndSucceeds() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    RegistrationHandle staleHandle =
        eventListeners.add(PLUGIN_NAME, new TargetEventListener());

    assertTrue(
        "Sanity check: stale listener should be registered",
        hasAnyEntry(eventListeners));

    // The stale TargetEventListener should be detected and removed proactively.
    newLifecycle(eventListeners).start();

    assertFalse(
        "Stale listener should have been removed",
        hasAnyTargetListenerEntry(eventListeners));
    // staleHandle.remove() is now a no-op because the entry was already CAS'd to null
    staleHandle.remove();
  }

  @Test
  public void startWarnsButSucceedsWhenListenerProviderThrows() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    Provider<EventListener> throwingProvider =
        () -> {
          throw new IllegalStateException("broken listener provider");
        };
    eventListeners.add(PLUGIN_NAME, throwingProvider);

    // Should succeed: the broken provider is logged as a warning,
    // the stale entry is removed, and the new listener is registered.
    newLifecycle(eventListeners).start();
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
  public void startsAndStopsDurableRequestExecutor() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    ReviewAiExecutors executors = mock(ReviewAiExecutors.class);
    EventHandlerExecutor eventHandlerExecutor = mock(EventHandlerExecutor.class);
    when(executors.getAgentExecutor()).thenReturn(command -> command.run());
    ReviewAiLifecycle lifecycle =
        new ReviewAiLifecycle(
            executors,
            eventHandlerExecutor,
            MOCK_LISTENER_PROVIDER,
            eventListeners,
            mock(ReviewAiDb.class),
            mock(ReviewConcernSanitizer.class),
            PLUGIN_NAME,
            TargetEventListener.class.getName());

    lifecycle.start();
    lifecycle.stop();

    verify(eventHandlerExecutor).start();
    verify(eventHandlerExecutor).stop();
  }

  @Test
  public void stopCallsStopManagedTcpServerIfOwner() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    ReviewAiDb reviewAiDb = mock(ReviewAiDb.class);

    ReviewAiLifecycle lifecycle = newLifecycle(eventListeners, reviewAiDb);
    lifecycle.stop();

    verify(reviewAiDb).stopManagedTcpServerIfOwner();
  }

  @Test
  public void fullRestartSucceedsWhenOldListenerIsProperlyRemoved() {
    // Simulates the correct Gerrit plugin reload sequence:
    // 1. Old plugin registers its listener
    // 2. Gerrit properly unloads the old plugin (removes its entries from DynamicSet)
    // 3. New plugin starts successfully because old listeners were cleaned up

    DynamicSet<EventListener> eventListeners = new DynamicSet<>();
    RegistrationHandle handle =
        eventListeners.add(PLUGIN_NAME, new TargetEventListener());

    assertTrue(
        "Sanity check: listener should be registered before removal",
        eventListeners.entries().iterator().hasNext());

    // Gerrit properly cleans up: remove the old plugin's listener
    handle.remove();

    // After proper cleanup, the new plugin start should succeed
    newLifecycle(eventListeners).start();
  }

  /**
   * Tests the actual Gerrit 3.14.2 reload ordering: new {@code start()} fires first and registers
   * the new listener, then old {@code stop()} fires. The old {@code stop()} must NOT remove the
   * new listener from the shared {@link DynamicSet}.
   */
  @Test
  public void stopDoesNotRemoveListenersRegisteredByAnotherLifecycle() {
    DynamicSet<EventListener> eventListeners = new DynamicSet<>();

    // Simulate old plugin incarnation: registers its listener
    ReviewAiLifecycle oldLifecycle = newLifecycle(eventListeners);
    oldLifecycle.start();

    assertTrue(
        "Old listener should be registered after old start()",
        hasAnyEntry(eventListeners));

    // Simulate new plugin incarnation: start() detects the stale old listener,
    // removes it via removeStaleListeners(), and registers the new one
    ReviewAiLifecycle newLifecycle = newLifecycle(eventListeners);
    newLifecycle.start();

    assertTrue(
        "New listener should be registered after new start()",
        hasAnyEntry(eventListeners));

    // Old plugin stop() fires (AFTER new start() — this is Gerrit's actual ordering)
    oldLifecycle.stop();

    assertTrue(
        "New listener must still be registered after old stop(); "
            + "old stop() must not sweep away the new plugin's listener",
        hasAnyEntry(eventListeners));
  }

  /**
   * Returns true if the DynamicSet has at least one entry whose plugin name matches ours and whose
   * listener class name matches TargetEventListener.
   */
  private static boolean hasAnyTargetListenerEntry(DynamicSet<EventListener> eventListeners) {
    for (Extension<EventListener> extension : eventListeners.entries()) {
      if (!PLUGIN_NAME.equals(extension.getPluginName())) {
        continue;
      }
      try {
        EventListener listener = extension.get();
        if (TargetEventListener.class.getName().equals(listener.getClass().getName())) {
          return true;
        }
      } catch (RuntimeException e) {
        // unreachable listener — still counts as an entry
        return true;
      }
    }
    return false;
  }

  private static boolean hasAnyEntry(DynamicSet<EventListener> eventListeners) {
    return eventListeners.entries().iterator().hasNext();
  }

  private static ReviewAiLifecycle newLifecycle(DynamicSet<EventListener> eventListeners) {
    return newLifecycle(eventListeners, mock(ReviewAiExecutors.class));
  }

  private static ReviewAiLifecycle newLifecycle(
      DynamicSet<EventListener> eventListeners, ReviewAiExecutors executors) {
    return newLifecycle(eventListeners, executors, mock(ReviewAiDb.class));
  }

  private static ReviewAiLifecycle newLifecycle(
      DynamicSet<EventListener> eventListeners, ReviewAiDb reviewAiDb) {
    return newLifecycle(eventListeners, mock(ReviewAiExecutors.class), reviewAiDb);
  }

  private static ReviewAiLifecycle newLifecycle(
      DynamicSet<EventListener> eventListeners,
      ReviewAiExecutors executors,
      ReviewAiDb reviewAiDb) {
    when(executors.getAgentExecutor()).thenReturn(command -> command.run());
    return new ReviewAiLifecycle(
        executors,
        mock(EventHandlerExecutor.class),
        MOCK_LISTENER_PROVIDER,
        eventListeners,
        reviewAiDb,
        mock(ReviewConcernSanitizer.class),
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
