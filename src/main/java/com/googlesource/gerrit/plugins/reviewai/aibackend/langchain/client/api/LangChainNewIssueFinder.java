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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;

final class LangChainNewIssueFinder {
  private final Configuration config;

  LangChainNewIssueFinder(Configuration config) {
    this.config = config;
  }

  <T> T find(
      ChangeSetData changeSetData,
      GerritChange change,
      ReviewerConcerns reviewedConcerns,
      String incrementalPatchSet,
      String fullPatchSet,
      RequestExecutor<T> requestExecutor)
      throws Exception {
    reviewedConcerns.normalize();
    if (incrementalPatchSet == null || incrementalPatchSet.isBlank()) {
      return null;
    }
    ChangeSetData finderData = changeSetData.copy();
    finderData.setReviewAssistantStage(ReviewAssistantStage.FIND_NEW_ISSUES);
    finderData.setForcedStagedReview(true);
    finderData.setReviewAssistantStageConversationSuffix(
        conversationSuffix(reviewedConcerns));
    finderData.setConcernWorkflowInput(
        LangChainConcernWorkflowInputFactory.create(
            config,
            reviewedConcerns,
            incrementalPatchSet,
            fullPatchSet,
            changeSetData.getReviewFeedbackMemory()));
    return requestExecutor.execute(finderData, change, "");
  }

  private String conversationSuffix(ReviewerConcerns concerns) {
    if (concerns.getReviewer() == null) {
      return "unknown";
    }
    return concerns.getReviewer().getKind() + "." + concerns.getReviewer().getName();
  }

  @FunctionalInterface
  interface RequestExecutor<T> {
    T execute(ChangeSetData changeSetData, GerritChange change, String patchSet)
        throws Exception;
  }
}
