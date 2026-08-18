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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernWorkflowInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;

final class LangChainConcernWorkflowInputFactory {
  private LangChainConcernWorkflowInputFactory() {}

  static ConcernWorkflowInput create(
      Configuration config,
      ReviewerConcerns concerns,
      String incrementalPatchSet,
      String fullPatchSet,
      ReviewFeedbackMemory reviewFeedback) {
    String fullPatchContext =
        config != null && config.getCodeContextPolicy() == CodeContextPolicies.NONE
            ? fullPatchSet
            : null;
    ReviewFeedbackMemory feedbackContext =
        concerns != null
                && concerns.getReviewer() != null
                && concerns.getReviewer().getKind()
                    == ConcernReviewerId.Kind.SINGLE_AGENT
            ? reviewFeedback
            : null;
    return new ConcernWorkflowInput(
        concerns, incrementalPatchSet, fullPatchContext, feedbackContext);
  }
}
