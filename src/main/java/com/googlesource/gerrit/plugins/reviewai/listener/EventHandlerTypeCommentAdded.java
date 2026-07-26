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

package com.googlesource.gerrit.plugins.reviewai.listener;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.LabelRequirement;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewer;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.google.gerrit.server.data.ApprovalAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
public class EventHandlerTypeCommentAdded implements IEventHandlerType {
  private final Configuration config;
  private final ChangeSetData changeSetData;
  private final GerritChange change;
  private final PatchSetReviewer reviewer;
  private final GerritClient gerritClient;
  private final boolean administratorUser;

  EventHandlerTypeCommentAdded(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      PatchSetReviewer reviewer,
      GerritClient gerritClient,
      boolean administratorUser) {
    this.config = config;
    this.changeSetData = changeSetData;
    this.change = change;
    this.reviewer = reviewer;
    this.gerritClient = gerritClient;
    this.administratorUser = administratorUser;
    log.debug(
        "Initialized EventHandlerTypeCommentAdded for full change ID: {}",
        change.getFullChangeId());
  }

  @Override
  public PreprocessResult preprocessEvent() {
    log.debug(
        "Starting preprocessing event for comment added on change ID: {}",
        change.getFullChangeId());

    // Check if this event carries label votes that satisfy required labels for review
    List<LabelRequirement> requiredLabels = config.getAiRequiredLabelsToStartReview();
    if (!requiredLabels.isEmpty()) {
      CommentAddedEvent commentEvent = (CommentAddedEvent) change.getPatchSetEvent();
      ApprovalAttribute[] eventApprovals = null;
      try {
        eventApprovals =
            commentEvent.approvals != null ? commentEvent.approvals.get() : null;
      } catch (Exception e) {
        log.debug(
            "Could not read event approvals for change {}: {}",
            change.getFullChangeId(),
            e.getMessage());
      }

      if (eventApprovals != null && eventApprovals.length > 0) {
        boolean relevantLabelChanged =
            Arrays.stream(eventApprovals)
                .anyMatch(
                    a ->
                        requiredLabels.stream()
                            .anyMatch(r -> r.getLabelName().equals(a.type)));

        if (relevantLabelChanged) {
          Integer existingAiVote = gerritClient.getCodeReviewValue(change);
          if (existingAiVote != null) {
            log.debug(
                "AI already reviewed change {} (existing Code-Review vote: {}), skipping",
                change.getFullChangeId(),
                existingAiVote);
          } else {
            Map<String, Short> currentApprovals = gerritClient.fetchCurrentApprovals(change);
            if (LabelRequirement.areSatisfied(
                requiredLabels, config.getAiRequiredLabelsStrategy(), currentApprovals)) {
              log.info(
                  "All required labels {} satisfied for change {} after label vote. "
                      + "Current approvals: {}. Triggering review.",
                  requiredLabels,
                  change.getFullChangeId(),
                  currentApprovals);
              changeSetData.setCurrentApprovals(currentApprovals);
              changeSetData.setForcedReview(true);
              return PreprocessResult.SWITCH_TO_PATCH_SET_CREATED;
            }
            log.debug(
                "Required labels {} not yet satisfied for change {}. Current approvals: {}",
                requiredLabels,
                change.getFullChangeId(),
                currentApprovals);
          }
        }
      }
    }

    if (!gerritClient.retrieveLastComments(change, administratorUser)) {
      log.debug("No new comments found for full change ID: {}", change.getFullChangeId());
      if (changeSetData.getForcedReview()) {
        log.info("Forcing review due to settings for full change ID: {}", change.getFullChangeId());
        return PreprocessResult.SWITCH_TO_PATCH_SET_CREATED;
      } else if (changeSetData.getReviewSystemMessage() != null) {
        log.info("Echoing system message in the UI");
        return PreprocessResult.OK;
      } else {
        log.info(
            "Exiting preprocessing as no comments require action for full change ID: {}",
            change.getFullChangeId());
        return PreprocessResult.EXIT;
      }
    } else {
      log.debug(
          "Comments retrieved during preprocessing for full change ID: {}",
          change.getFullChangeId());
    }
    change.setIsCommentEvent(true);
    return PreprocessResult.OK;
  }

  @Override
  public void processEvent() throws Exception {
    log.debug(
        "Processing event to review comments on full change ID: {}", change.getFullChangeId());
    reviewer.review(change, administratorUser);
    log.debug(
        "Completed processing event for reviewing comments on full change ID: {}",
        change.getFullChangeId());
  }
}
