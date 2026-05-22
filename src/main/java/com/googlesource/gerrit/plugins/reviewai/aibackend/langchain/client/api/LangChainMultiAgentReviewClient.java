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
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.LangChainMemoryId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.messages.LangChainChatMessages;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.LangChainProviderFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt.AiPromptReviewAgentRouter;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
  private static final String ROUTER_SCOPE = "router";

  private final Executor executor;

  @Inject
  public LangChainMultiAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      PluginChatMemoryStore chatMemoryStore) {
    this(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        pluginDataHandlerProvider,
        chatMemoryStore,
        ForkJoinPool.commonPool());
  }

  @VisibleForTesting
  public LangChainMultiAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Executor executor) {
    this(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        pluginDataHandlerProvider,
        null,
        executor);
  }

  @VisibleForTesting
  public LangChainMultiAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      Executor executor) {
    this(config, codeContextPolicy, gerritClient, localizer, null, null, executor);
  }

  @VisibleForTesting
  public LangChainMultiAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      PluginChatMemoryStore chatMemoryStore,
      Executor executor) {
    super(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        pluginDataHandlerProvider,
        chatMemoryStore);
    this.executor = executor;
    log.debug("Initialized LangChainMultiAgentReviewClient.");
  }

  @Override
  public AiResponseContent ask(ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws Exception {
    log.debug(
        "Multi-agent LangChain ask method called with changeId: {}", change.getFullChangeId());
    if (change.getIsCommentEvent() && !changeSetData.getForcedReview()) {
      ReviewAssistantStages routedStage = routeMessage(changeSetData, change);
      log.debug("LangChain routing agent selected stage {} for message", routedStage);
      ReviewRequestResult reviewRequestResult =
          askStage(changeSetData, change, patchSet, routedStage);
      setRequestBody(reviewRequestResult == null ? null : reviewRequestResult.getRequestBody());
      return reviewRequestResult == null ? null : reviewRequestResult.getResponseContent();
    }
    if (changeSetData.getForcedStagedReview()) {
      return askStages(
          changeSetData, change, patchSet, List.of(changeSetData.getReviewAssistantStage()));
    }
    return askStages(changeSetData, change, patchSet, MULTI_AGENT_ASSISTANT_STAGES);
  }

  private AiResponseContent askStages(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      List<ReviewAssistantStages> assistantStages)
      throws Exception {
    List<CompletableFuture<ReviewRequestResult>> reviewRequestFutures = new ArrayList<>();
    for (ReviewAssistantStages assistantStage : assistantStages) {
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
    stageChangeSetData.setForcedStagedReview(true);
    log.debug("Processing LangChain stage: {}", assistantStage);
    return askSingleRequest(stageChangeSetData, change, patchSet);
  }

  @VisibleForTesting
  protected ReviewAssistantStages routeMessage(ChangeSetData changeSetData, GerritChange change)
      throws AiConnectionFailException {
    LangChainMemoryId routerMemoryId =
        new LangChainMemoryId(
            change.getFullChangeId(), LangChainMemoryId.getPatchSetNumber(change), ROUTER_SCOPE);
    AiProviderType providerType = config.getAiProviderType();
    ChatMemory memory =
        providerType == AiProviderType.OPENAI
            ? buildTransientMemory(routerMemoryId)
            : buildMemory(routerMemoryId);
    AiPromptReviewAgentRouter routerPrompt = new AiPromptReviewAgentRouter(config);
    String routerInstructions = routerPrompt.getDefaultAiAssistantInstructions();
    if (memory.messages().isEmpty() && providerType != AiProviderType.OPENAI) {
      memory.add(LangChainChatMessages.systemMessage(routerInstructions));
    }
    String requestData =
        changeSetData.getAiDataPrompt() == null ? "" : changeSetData.getAiDataPrompt();
    memory.add(
        LangChainChatMessages.userMessage(routerPrompt.getDefaultAiThreadReviewMessage(requestData)));

    ILangChainProvider provider = LangChainProviderFactory.get(providerType);
    LangChainProvider providerModel =
        provider.buildChatModel(config, 0.0, null, routerInstructions);
    ChatModel model = providerModel.getModel();
    log.info(
        "LangChain routing request for {} using provider {} model {} (endpoint={})",
        routerMemoryId,
        providerType,
        config.getAiModel(),
        providerModel.getEndpoint());

    try {
      ChatResponse response = model.chat(memory.messages());
      AiMessage aiMessage = response == null ? null : response.aiMessage();
      if (aiMessage != null) {
        memory.add(aiMessage);
      }
      return parseRoute(aiMessage == null ? null : aiMessage.text());
    } catch (Exception e) {
      throw new AiConnectionFailException(e);
    }
  }

  private ReviewAssistantStages parseRoute(String route) {
    if (route == null) {
      log.warn("LangChain routing agent returned null route; defaulting to patchset agent");
      return ReviewAssistantStages.REVIEW_CODE;
    }
    String normalized = route.trim().toUpperCase(Locale.ROOT);
    if (normalized.contains("COMMIT_MESSAGE") || normalized.contains("COMMIT MESSAGE")) {
      return ReviewAssistantStages.REVIEW_COMMIT_MESSAGE;
    }
    if (!normalized.contains("PATCHSET")) {
      log.warn(
          "LangChain routing agent returned unrecognized route `{}`; defaulting to patchset agent",
          route);
    }
    return ReviewAssistantStages.REVIEW_CODE;
  }
}
