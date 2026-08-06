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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.isJsonObjectAsString;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.unwrapJsonCode;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernStatusUpdater;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiCostTracker;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ResponseFormat;
import java.util.List;
import java.util.function.Function;

final class LangChainConcernReviewer {
  private static final String RESPONSE_SCHEMA_RESOURCE =
      "config/formatConcernReviewSchema.json";

  private final ResponseFormat responseFormat;
  private final LangChainExecutor toolExecutor;

  LangChainConcernReviewer(
      Configuration config,
      List<ToolSpecification> contextTools,
      boolean requireInitialToolUse,
      GitRepoFiles gitRepoFiles,
      AiCostTracker costTracker,
      Function<ResponseFormat, ResponseFormat> providerResponseFormat) {
    responseFormat =
        new LangChainStructuredResponseFactory(RESPONSE_SCHEMA_RESOURCE)
            .loadStructuredResponseFormat();
    toolExecutor =
        new LangChainExecutor(
            config,
            providerResponseFormat.apply(responseFormat),
            contextTools,
            requireInitialToolUse,
            gitRepoFiles,
            costTracker);
  }

  ReviewerConcerns review(
      ChangeSetData changeSetData,
      GerritChange change,
      ReviewerConcerns existingConcerns,
      String incrementalPatchSet,
      RequestExecutor requestExecutor)
      throws Exception {
    existingConcerns.normalize();
    if (existingConcerns.getConcerns().isEmpty()) {
      return existingConcerns;
    }

    ChangeSetData concernReviewData = changeSetData.copy();
    concernReviewData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_CONCERNS);
    concernReviewData.setReviewAssistantStageConversationSuffix(
        conversationSuffix(existingConcerns));
    concernReviewData.setConcernsToReview(existingConcerns);
    String responseText =
        requestExecutor.execute(
            concernReviewData, change, incrementalPatchSet == null ? "" : incrementalPatchSet);
    if (responseText == null || !isJsonObjectAsString(responseText)) {
      throw new IllegalStateException("Concern reviewer returned no structured response");
    }

    ReviewerConcerns response =
        getGson().fromJson(unwrapJsonCode(responseText), ReviewerConcerns.class);
    List<ReviewConcern> updatedConcerns =
        ReviewConcernStatusUpdater.apply(
            existingConcerns.getConcerns(), response == null ? null : response.getConcerns());
    ReviewerConcerns result = new ReviewerConcerns();
    result.setReviewer(existingConcerns.getReviewer());
    result.setConcerns(updatedConcerns);
    return result;
  }

  ResponseFormat getResponseFormat() {
    return responseFormat;
  }

  LangChainExecutor getToolExecutor() {
    return toolExecutor;
  }

  private String conversationSuffix(ReviewerConcerns concerns) {
    if (concerns.getReviewer() == null) {
      return "unknown";
    }
    return concerns.getReviewer().getKind() + "." + concerns.getReviewer().getName();
  }

  @FunctionalInterface
  interface RequestExecutor {
    String execute(ChangeSetData changeSetData, GerritChange change, String patchSet)
        throws Exception;
  }
}
