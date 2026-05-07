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

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai.AiResponseContentMerger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class LangChainMultiAgentReviewClient extends LangChainClient implements IAiClient {
  private static final List<ReviewAssistantStages> MULTI_AGENT_ASSISTANT_STAGES =
      List.of(ReviewAssistantStages.REVIEW_CODE, ReviewAssistantStages.REVIEW_COMMIT_MESSAGE);

  private final Executor executor;

  @Inject
  public LangChainMultiAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer) {
    this(config, codeContextPolicy, gerritClient, localizer, ForkJoinPool.commonPool());
  }

  @VisibleForTesting
  public LangChainMultiAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      Executor executor) {
    super(config, codeContextPolicy, gerritClient, localizer);
    this.executor = executor;
    log.debug("Initialized LangChainMultiAgentReviewClient.");
  }

  @Override
  public AiResponseContent ask(ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws Exception {
    log.debug("Multi-agent LangChain ask method called with changeId: {}", change.getFullChangeId());
    if (change.getIsCommentEvent() || changeSetData.getForcedStagedReview()) {
      return super.ask(changeSetData, change, patchSet);
    }

    List<CompletableFuture<ReviewRequestResult>> reviewRequestFutures = new ArrayList<>();
    for (ReviewAssistantStages assistantStage : MULTI_AGENT_ASSISTANT_STAGES) {
      reviewRequestFutures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return askStage(changeSetData, change, patchSet, assistantStage);
                } catch (Exception e) {
                  throw new CompletionException(e);
                }
              },
              executor));
    }

    List<AiResponseContent> aiResponseContents = new ArrayList<>();
    ReviewRequestResult latestReviewRequest = null;
    try {
      for (CompletableFuture<ReviewRequestResult> reviewRequestFuture : reviewRequestFutures) {
        ReviewRequestResult reviewRequestResult = reviewRequestFuture.join();
        latestReviewRequest = reviewRequestResult;
        aiResponseContents.add(reviewRequestResult.getResponseContent());
      }
    } catch (CompletionException e) {
      if (e.getCause() instanceof Exception exception) {
        throw exception;
      }
      throw e;
    }

    if (latestReviewRequest != null) {
      setRequestBody(latestReviewRequest.getRequestBody());
    }
    return AiResponseContentMerger.merge(aiResponseContents);
  }

  private ReviewRequestResult askStage(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      ReviewAssistantStages assistantStage)
      throws Exception {
    ChangeSetData stageChangeSetData = changeSetData.copy();
    stageChangeSetData.setReviewAssistantStage(assistantStage);
    log.debug("Processing LangChain stage: {}", assistantStage);
    return askSingleRequest(stageChangeSetData, change, patchSet);
  }

}
