/*
 * Copyright (c) 2026. Amarula Solutions
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

package com.googlesource.gerrit.plugins.reviewai.listener;

import com.google.gerrit.server.events.ChangeAbandonedEvent;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetEvent;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritChangeRef;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestStore;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewChangeStateStore;
import lombok.extern.slf4j.Slf4j;

/** Handles operational review-state cleanup when a change is merged or abandoned. */
@Slf4j
@Singleton
public class ClosedChangeLifecycleEventHandler {
  private final ReviewChangeStateStore reviewChangeStateStore;
  private final AiRequestCoordinator aiRequestCoordinator;
  private final AiRequestStore aiRequestStore;

  @Inject
  ClosedChangeLifecycleEventHandler(
      ReviewChangeStateStore reviewChangeStateStore,
      AiRequestCoordinator aiRequestCoordinator,
      AiRequestStore aiRequestStore) {
    this.reviewChangeStateStore = reviewChangeStateStore;
    this.aiRequestCoordinator = aiRequestCoordinator;
    this.aiRequestStore = aiRequestStore;
  }

  /** Returns whether the event was a supported lifecycle event and was consumed. */
  public boolean handle(Event event) {
    if (!(event instanceof ChangeMergedEvent) && !(event instanceof ChangeAbandonedEvent)) {
      return false;
    }

    GerritChange change = new GerritChange(event);
    GerritChangeRef changeRef = changeRef((PatchSetEvent) event);
    cancelActiveReview(changeRef, change, event);
    deleteAiRequests(changeRef, change);
    clearReviewStateWhenIdle(changeRef, change, event);
    return true;
  }

  private void clearReviewStateWhenIdle(
      GerritChangeRef changeRef, GerritChange change, Event event) {
    try {
      aiRequestCoordinator.runWhenChangeIdle(changeRef, () -> clearReviewState(change, event));
    } catch (Exception e) {
      log.error(
          "Failed to schedule final review state cleanup for change {}",
          change.getFullChangeId(),
          e);
    }
  }

  private void clearReviewState(GerritChange change, Event event) {
    log.debug(
        "Clearing operational review state for change {} on event {}",
        change.getFullChangeId(),
        event.getType());
    try {
      reviewChangeStateStore.clear(change.getFullChangeId());
    } catch (Exception e) {
      log.error("Failed to clear review state for change {}", change.getFullChangeId(), e);
    }
  }

  private void cancelActiveReview(GerritChangeRef changeRef, GerritChange change, Event event) {
    String reason =
        event instanceof ChangeMergedEvent ? "Change merged" : "Change abandoned";
    try {
      aiRequestCoordinator.cancelRunningReview(changeRef, reason);
    } catch (Exception e) {
      log.error("Failed to cancel active AI review for change {}", change.getFullChangeId(), e);
    }
  }

  private void deleteAiRequests(GerritChangeRef changeRef, GerritChange change) {
    try {
      aiRequestStore.deleteByChange(changeRef);
    } catch (Exception e) {
      log.error("Failed to delete AI requests for change {}", change.getFullChangeId(), e);
    }
  }

  private static GerritChangeRef changeRef(PatchSetEvent event) {
    if (event.change == null || event.change.get() == null) {
      throw new IllegalArgumentException("change event data is required");
    }
    return new GerritChangeRef(event.instanceId, event.change.get().number);
  }
}
