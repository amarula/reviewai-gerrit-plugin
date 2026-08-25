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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai.ReviewConcernLedgerOperations;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import java.util.List;

final class LangChainSingleAgentConcernWorkflow {
  private static final ConcernReviewerId REVIEWER =
      new ConcernReviewerId(ConcernReviewerId.Kind.SINGLE_AGENT, "PATCHSET");

  private final Configuration config;
  private final ReviewConcernLedgerOperations ledgerOperations;
  private final FeedbackReview feedbackReview;
  private final InitialReview initialReview;
  private final ConcernReview concernReview;
  private final NewIssueReview newIssueReview;

  LangChainSingleAgentConcernWorkflow(
      Configuration config,
      ReviewConcernLedgerOperations ledgerOperations,
      FeedbackReview feedbackReview,
      InitialReview initialReview,
      ConcernReview concernReview,
      NewIssueReview newIssueReview) {
    this.config = config;
    this.ledgerOperations = ledgerOperations;
    this.feedbackReview = feedbackReview;
    this.initialReview = initialReview;
    this.concernReview = concernReview;
    this.newIssueReview = newIssueReview;
  }

  boolean applies(ChangeSetData changeSetData, GerritChange change) {
    return isConfigured()
        && (!Boolean.TRUE.equals(change.getIsCommentEvent())
            || Boolean.TRUE.equals(changeSetData.getForcedReview()));
  }

  boolean isConfigured() {
    return config != null
        && config.getAgentSpecializationLevel() == AgentSpecializationLevel.SINGLE_AGENT;
  }

  ReviewResult review(
      ChangeSetData changeSetData, GerritChange change, String fullPatchSet)
      throws Exception {
    ReviewFeedbackMemory feedback = feedbackReview.review(changeSetData, change);
    changeSetData.setReviewFeedbackMemory(feedback);
    ReviewConcernLedger previousLedger = changeSetData.getPreviousReviewConcernLedger();
    if (previousLedger == null) {
      ReviewResult firstReview =
          initialReview.review(changeSetData, change, fullPatchSet);
      if (firstReview == null) {
        return null;
      }
      return new ReviewResult(
          ledgerOperations.initializeLedger(
              firstReview.responseContent(), change, REVIEWER),
          firstReview.requestBody());
    }

    ReviewerConcerns existingConcerns =
        ledgerOperations.reviewerConcerns(previousLedger, REVIEWER);
    ReviewerConcerns reviewedConcerns =
        concernReview.review(
            changeSetData,
            change,
            existingConcerns,
            changeSetData.getIncrementalPatchSet(),
            fullPatchSet);
    ReviewConcernLedger reviewedLedger = new ReviewConcernLedger();
    reviewedLedger.setReviewers(List.of(reviewedConcerns));
    reviewedConcerns =
        ledgerOperations
            .markDisabledScopeConcernsSkipped(reviewedLedger, feedback)
            .getReviewers()
            .getFirst();
    ReviewResult newIssues =
        newIssueReview.review(
            changeSetData,
            change,
            reviewedConcerns,
            changeSetData.getIncrementalPatchSet(),
            fullPatchSet);
    if (newIssues == null) {
      return null;
    }
    return new ReviewResult(
        ledgerOperations.completeFollowUp(
            newIssues.responseContent(), change, previousLedger, reviewedConcerns),
        newIssues.requestBody());
  }

  @FunctionalInterface
  interface FeedbackReview {
    ReviewFeedbackMemory review(ChangeSetData changeSetData, GerritChange change)
        throws Exception;
  }

  @FunctionalInterface
  interface InitialReview {
    ReviewResult review(
        ChangeSetData changeSetData, GerritChange change, String patchSet)
        throws Exception;
  }

  @FunctionalInterface
  interface ConcernReview {
    ReviewerConcerns review(
        ChangeSetData changeSetData,
        GerritChange change,
        ReviewerConcerns existingConcerns,
        String incrementalPatchSet,
        String fullPatchSet)
        throws Exception;
  }

  @FunctionalInterface
  interface NewIssueReview {
    ReviewResult review(
        ChangeSetData changeSetData,
        GerritChange change,
        ReviewerConcerns reviewedConcerns,
        String incrementalPatchSet,
        String fullPatchSet)
        throws Exception;
  }

  record ReviewResult(AiResponseContent responseContent, String requestBody) {}
}
