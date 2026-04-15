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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

@Slf4j
@Singleton
public class OpenAiTaskSpecificReviewClient extends OpenAiReviewClient implements IAiClient {
  private static final List<ReviewAssistantStages> TASK_SPECIFIC_ASSISTANT_STAGES =
      List.of(ReviewAssistantStages.REVIEW_CODE, ReviewAssistantStages.REVIEW_COMMIT_MESSAGE);
  private final Executor executor;

  @Inject
  public OpenAiTaskSpecificReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider) {
    this(config, codeContextPolicy, pluginDataHandlerProvider, ForkJoinPool.commonPool());
  }

  @VisibleForTesting
  public OpenAiTaskSpecificReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Executor executor) {
    super(config, codeContextPolicy, pluginDataHandlerProvider);
    this.executor = executor;
    log.debug("Initialized OpenAiTaskSpecificReviewClient.");
  }

  public AiResponseContent ask(
      ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws AiConnectionFailException {
    log.debug("Task-specific OpenAI ask method called with changeId: {}", change.getFullChangeId());
    if (change.getIsCommentEvent()) {
      return super.ask(changeSetData, change, patchSet);
    }
    List<CompletableFuture<ReviewRequestResult>> reviewRequestFutures = new ArrayList<>();
    for (ReviewAssistantStages assistantStage : TASK_SPECIFIC_ASSISTANT_STAGES) {
      reviewRequestFutures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  return askStage(changeSetData, change, patchSet, assistantStage);
                } catch (AiConnectionFailException e) {
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
      if (e.getCause() instanceof AiConnectionFailException aiConnectionFailException) {
        throw aiConnectionFailException;
      }
      throw e;
    }

    if (latestReviewRequest != null) {
      requestBody = latestReviewRequest.getRequestBody();
      String conversationId = latestReviewRequest.getConversationId();
      if (conversationId != null) {
        getPluginDataHandlerProvider()
            .getChangeScope()
            .setValue(OpenAiConversation.KEY_CONVERSATION_ID, conversationId);
      }
    }
    return mergeResponses(aiResponseContents);
  }

  private ReviewRequestResult askStage(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      ReviewAssistantStages assistantStage)
      throws AiConnectionFailException {
    ChangeSetData stageChangeSetData = changeSetData.copy();
    stageChangeSetData.setReviewAssistantStage(assistantStage);
    log.debug("Processing stage: {}", assistantStage);
    return askWithConversationKey(
        stageChangeSetData,
        change,
        patchSet,
        OpenAiConversation.getTaskSpecificConversationKey(assistantStage));
  }

  private AiResponseContent mergeResponses(List<AiResponseContent> aiResponseContents) {
    log.debug("Merging responses from different task-specific stages.");
    AiResponseContent mergedResponse = aiResponseContents.remove(0);
    for (AiResponseContent aiResponseContent : aiResponseContents) {
      List<AiReplyItem> replies = aiResponseContent.getReplies();
      if (replies != null) {
        mergedResponse.getReplies().addAll(replies);
      } else {
        mergedResponse.setMessageContent(aiResponseContent.getMessageContent());
      }
    }
    log.debug("Merged response content: {}", mergedResponse.getMessageContent());
    return mergedResponse;
  }
}
