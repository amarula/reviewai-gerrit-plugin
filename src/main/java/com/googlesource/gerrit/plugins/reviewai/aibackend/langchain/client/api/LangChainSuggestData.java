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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;

final class LangChainSuggestData {
  private LangChainSuggestData() {}

  static ChangeSetData review(ChangeSetData changeSetData) {
    ChangeSetData reviewData = changeSetData.copy();
    reviewData.setForcedReview(true);
    reviewData.setSuggestMode(false);
    ReviewScope scope = changeSetData.getReviewScope();
    if (scope == ReviewScope.PATCHSET || scope == ReviewScope.COMMIT_MESSAGE) {
      reviewData.setForcedStagedReview(true);
      reviewData.setReviewAssistantStage(toReviewAssistantStage(scope));
    } else {
      reviewData.setForcedStagedReview(false);
    }
    return reviewData;
  }

  static ChangeSetData suggestion(ChangeSetData changeSetData) {
    return suggestion(changeSetData, changeSetData.getReviewScope());
  }

  static ChangeSetData suggestion(ChangeSetData changeSetData, ReviewScope scope) {
    ChangeSetData suggestionData = changeSetData.copy();
    suggestionData.setForcedReview(true);
    suggestionData.setForcedStagedReview(true);
    suggestionData.setSuggestMode(true);
    if (scope == ReviewScope.PATCHSET || scope == ReviewScope.COMMIT_MESSAGE) {
      suggestionData.setReviewScope(scope);
      suggestionData.setReviewAssistantStage(toReviewAssistantStage(scope));
    }
    return suggestionData;
  }

  private static ReviewAssistantStage toReviewAssistantStage(ReviewScope scope) {
    return scope == ReviewScope.COMMIT_MESSAGE
        ? ReviewAssistantStage.REVIEW_COMMIT_MESSAGE
        : ReviewAssistantStage.REVIEW_CODE;
  }
}
