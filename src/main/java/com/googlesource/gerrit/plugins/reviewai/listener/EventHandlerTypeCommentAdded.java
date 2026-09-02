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

import com.google.gerrit.server.data.ApprovalAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.AiReviewConditionLabelResolver;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewer;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackPublisher;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EventHandlerTypeCommentAdded implements IEventHandlerType {
  private final Configuration config;
  private final ChangeSetData changeSetData;
  private final GerritChange change;
  private final PatchSetReviewer reviewer;
  private final GerritClient gerritClient;
  private final AiReviewApplicabilityChecker aiReviewApplicabilityChecker;
  private final ReviewFeedbackPublisher reviewFeedbackPublisher;
  private final boolean administratorUser;

  EventHandlerTypeCommentAdded(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      PatchSetReviewer reviewer,
      GerritClient gerritClient,
      AiReviewApplicabilityChecker aiReviewApplicabilityChecker,
      ReviewFeedbackPublisher reviewFeedbackPublisher,
      boolean administratorUser) {
    this.config = config;
    this.changeSetData = changeSetData;
    this.change = change;
    this.reviewer = reviewer;
    this.gerritClient = gerritClient;
    this.aiReviewApplicabilityChecker = aiReviewApplicabilityChecker;
    this.reviewFeedbackPublisher = reviewFeedbackPublisher;
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
    if (shouldStartDeferredReview()) {
      changeSetData.setForcedReview(true);
      changeSetData.setDeferredReview(true);
      return PreprocessResult.SWITCH_TO_PATCH_SET_CREATED;
    }
    boolean commentsRetrieved =
        gerritClient.retrieveComments(change, administratorUser);
    enqueueAddressedComments();
    if (!commentsRetrieved) {
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

  private void enqueueAddressedComments() {
    GerritClientData clientData = gerritClient.getClientData(change);
    CommentData commentData = clientData == null ? null : clientData.getCommentData();
    if (commentData == null || commentData.getAddressedComments() == null) {
      return;
    }
    reviewFeedbackPublisher.enqueue(
        change,
        commentData.getAddressedComments().stream()
            .filter(Objects::nonNull)
            .map(comment -> comment.getId())
            .filter(Objects::nonNull)
            .filter(id -> !id.isBlank())
            .toList());
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

  private boolean shouldStartDeferredReview() {
    String applicableIf = config.getAiReviewApplicableIf();
    if (applicableIf.isBlank() || !hasConditionLabelTransition(applicableIf)) {
      return false;
    }
    if (!aiReviewApplicabilityChecker.isApplicable(change, applicableIf)) {
      return false;
    }
    log.info(
        "AI review applicability expression '{}' is satisfied after a condition label update for"
            + " change {}",
        applicableIf,
        change.getFullChangeId());
    return true;
  }

  private boolean hasConditionLabelTransition(String applicableIf) {
    CommentAddedEvent commentEvent = (CommentAddedEvent) change.getPatchSetEvent();
    try {
      ApprovalAttribute[] approvals =
          commentEvent.approvals == null ? null : commentEvent.approvals.get();
      Set<String> conditionLabels =
          AiReviewConditionLabelResolver.extractConditionLabels(applicableIf);
      return approvals != null
          && Arrays.stream(approvals)
              .anyMatch(
                  approval ->
                      isConditionLabel(approval.type, conditionLabels)
                          && hasChangedStatus(approval));
    } catch (RuntimeException e) {
      log.warn("Could not read approval updates for change {}", change.getFullChangeId(), e);
      return false;
    }
  }

  private static boolean isConditionLabel(String label, Set<String> conditionLabels) {
    return label != null
        && conditionLabels.stream().anyMatch(labelName -> labelName.equalsIgnoreCase(label));
  }

  private static boolean hasChangedStatus(ApprovalAttribute approval) {
    // Gerrit leaves oldValue == null when a submitted vote is unchanged.
    return approval.oldValue != null && !Objects.equals(approval.oldValue, approval.value);
  }
}
