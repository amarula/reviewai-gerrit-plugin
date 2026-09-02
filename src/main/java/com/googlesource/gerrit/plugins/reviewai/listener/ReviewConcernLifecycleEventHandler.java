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
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestStore;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import lombok.extern.slf4j.Slf4j;

/** Handles concern-ledger cleanup when a change is merged or abandoned. */
@Slf4j
@Singleton
public class ReviewConcernLifecycleEventHandler {
  private final ReviewConcernPublisher reviewConcernPublisher;
  private final AiRequestCoordinator aiRequestCoordinator;
  private final AiRequestStore aiRequestStore;

  @Inject
  ReviewConcernLifecycleEventHandler(
      ReviewConcernPublisher reviewConcernPublisher,
      AiRequestCoordinator aiRequestCoordinator,
      AiRequestStore aiRequestStore) {
    this.reviewConcernPublisher = reviewConcernPublisher;
    this.aiRequestCoordinator = aiRequestCoordinator;
    this.aiRequestStore = aiRequestStore;
  }

  /** Returns whether the event was a supported lifecycle event and was consumed. */
  public boolean handle(Event event) {
    if (!(event instanceof ChangeMergedEvent) && !(event instanceof ChangeAbandonedEvent)) {
      return false;
    }

    GerritChange change = new GerritChange(event);
    cancelActiveReview(change, event);
    deleteAiRequests(change);
    log.debug(
        "Clearing review concern ledger for change {} on event {}",
        change.getFullChangeId(),
        event.getType());
    try {
      reviewConcernPublisher.clear(change);
    } catch (Exception e) {
      log.error(
          "Failed to clear review concern ledger for change {}", change.getFullChangeId(), e);
    }
    return true;
  }

  private void cancelActiveReview(GerritChange change, Event event) {
    String reason =
        event instanceof ChangeMergedEvent ? "Change merged" : "Change abandoned";
    try {
      aiRequestCoordinator.cancelRunningReview(change.getFullChangeId(), reason);
    } catch (Exception e) {
      log.error("Failed to cancel active AI review for change {}", change.getFullChangeId(), e);
    }
  }

  private void deleteAiRequests(GerritChange change) {
    try {
      aiRequestStore.deleteByChange(change.getFullChangeId());
    } catch (Exception e) {
      log.error("Failed to delete AI requests for change {}", change.getFullChangeId(), e);
    }
  }
}
