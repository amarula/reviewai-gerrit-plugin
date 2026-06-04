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
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai.AiResponseContentMerger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritAiReviewHistoryCollector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiHistory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiMessageItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.LangChainMemoryId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.messages.LangChainChatMessages;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.LangChainProviderFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptReviewAgentRouter;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import com.googlesource.gerrit.plugins.reviewai.web.ReviewAgentConversationStore;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

@Slf4j
@Singleton
public class LangChainMultiAgentReviewClient extends LangChainClient implements IAiClient {
  private static final List<ReviewAssistantStage> MULTI_AGENT_ASSISTANT_STAGES =
      List.of(ReviewAssistantStage.REVIEW_CODE, ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
  private static final String ROUTER_SCOPE = "router";

  private final Executor executor;
  private final GerritClient gerritClient;
  private final Localizer localizer;
  private final GerritAiReviewHistoryCollector aiReviewHistoryCollector;
  private final ReviewAgentConversationStore conversationStore;

  @Inject
  public LangChainMultiAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      ReviewAgentConversationStore conversationStore,
      PluginChatMemoryStore chatMemoryStore) {
    this(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        pluginDataHandlerProvider,
        conversationStore,
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
        null,
        executor);
  }

  @VisibleForTesting
  public LangChainMultiAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      ReviewAgentConversationStore conversationStore,
      Executor executor) {
    this(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        null,
        conversationStore,
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
    this(config, codeContextPolicy, gerritClient, localizer, null, null, null, executor);
  }

  @VisibleForTesting
  public LangChainMultiAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      ReviewAgentConversationStore conversationStore,
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
    this.gerritClient = gerritClient;
    this.localizer = localizer;
    this.aiReviewHistoryCollector = new GerritAiReviewHistoryCollector();
    this.conversationStore = conversationStore;
    log.debug("Initialized LangChainMultiAgentReviewClient.");
  }

  @Override
  public AiResponseContent ask(ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws Exception {
    log.debug(
        "Multi-agent LangChain ask method called with changeId: {}", change.getFullChangeId());
    if (changeSetData.getSuggestMode()) {
      return getSuggestClient().ask(changeSetData, change, patchSet);
    }
    return askReview(changeSetData, change, patchSet);
  }

  @Override
  protected AiResponseContent askReview(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    if (change.getIsCommentEvent() && !changeSetData.getForcedReview()) {
      ReviewAssistantStage routedStage = routeMessage(changeSetData, change);
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
      List<ReviewAssistantStage> assistantStages)
      throws Exception {
    List<CompletableFuture<ReviewRequestResult>> reviewRequestFutures = new ArrayList<>();
    for (ReviewAssistantStage assistantStage : assistantStages) {
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
      ReviewAssistantStage assistantStage)
      throws Exception {
    ChangeSetData stageChangeSetData = changeSetData.copy();
    stageChangeSetData.setReviewAssistantStage(assistantStage);
    stageChangeSetData.setForcedStagedReview(true);
    log.debug("Processing LangChain stage: {}", assistantStage);
    return askSingleRequest(stageChangeSetData, change, patchSet);
  }

  @VisibleForTesting
  protected ReviewAssistantStage routeMessage(ChangeSetData changeSetData, GerritChange change)
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
    if (providerType != AiProviderType.OPENAI) {
      memory.add(LangChainChatMessages.systemMessage(routerInstructions));
    }
    String requestData =
        changeSetData.getAiDataPrompt() == null ? "" : changeSetData.getAiDataPrompt();
    List<ChatMessage> routingHistoryMessages =
        buildRoutingContextMessages(changeSetData, change, requestData);
    for (ChatMessage message : routingHistoryMessages) {
      memory.add(message);
    }
    String routerUserPrompt = routerPrompt.getDefaultAiThreadReviewMessage(requestData);
    memory.add(LangChainChatMessages.userMessage(routerUserPrompt));

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
    logRoutingAgentPrompt(routerMemoryId, routerInstructions, routerUserPrompt, memory.messages());

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

  private void logRoutingAgentPrompt(
      LangChainMemoryId routerMemoryId,
      String routerInstructions,
      String routerUserPrompt,
      List<ChatMessage> messages) {
    if (!log.isDebugEnabled()) {
      return;
    }
    log.debug("LangChain routing agent instructions for {}: {}", routerMemoryId, routerInstructions);
    log.debug("LangChain routing agent user prompt for {}: {}", routerMemoryId, routerUserPrompt);
    log.debug(
        "LangChain routing agent message stack for {} with {} messages:{}",
        routerMemoryId,
        messages.size(),
        messages);
  }

  @VisibleForTesting
  protected List<ChatMessage> buildRoutingContextMessages(
      ChangeSetData changeSetData, GerritChange change, String requestData) {
    List<ChatMessage> messages = new ArrayList<>();
    Set<String> seenMessages = new HashSet<>();
    LangChainChatMessages.appendUnique(
        messages, seenMessages, buildStoredAutomaticReviewMessages(change));
    LangChainChatMessages.appendUnique(
        messages, seenMessages, buildFullGerritHistoryMessages(changeSetData, change));
    LangChainChatMessages.appendUnique(
        messages, seenMessages, buildAiReviewMessages(changeSetData, change));
    LangChainChatMessages.appendUnique(
        messages, seenMessages, buildRoutingHistoryMessages(requestData));
    return messages;
  }

  @VisibleForTesting
  protected List<ChatMessage> buildRoutingHistoryMessages(String requestData) {
    if (requestData == null || requestData.isBlank()) {
      return List.of();
    }

    try {
      AiMessageItem[] messageItems = getGson().fromJson(requestData, AiMessageItem[].class);
      if (messageItems == null) {
        return List.of();
      }
      List<ChatMessage> messages = new ArrayList<>();
      for (AiMessageItem messageItem : messageItems) {
        if (messageItem == null) {
          continue;
        }
        messages.addAll(LangChainChatMessages.fromRequestMessages(messageItem.getHistory()));
        String request = messageItem.getRequest();
        if (request != null && !request.isBlank()) {
          messages.add(LangChainChatMessages.userMessage(request));
        }
      }
      return messages;
    } catch (JsonSyntaxException e) {
      log.debug("Unable to parse router request data as message history", e);
      return List.of();
    }
  }

  @VisibleForTesting
  protected List<ChatMessage> buildFullGerritHistoryMessages(
      ChangeSetData changeSetData, GerritChange change) {
    if (gerritClient == null || localizer == null || changeSetData == null || change == null) {
      return List.of();
    }

    try {
      GerritClientData gerritClientData = gerritClient.getClientData(change);
      if (gerritClientData == null) {
        return List.of();
      }
      AiHistory aiHistory = new AiHistory(config, changeSetData, gerritClientData, localizer);
      return LangChainChatMessages.build(aiHistory, gerritClientData, change).stream()
          .map(LangChainChatMessages::trimmed)
          .toList();
    } catch (Exception e) {
      log.debug("Unable to add full Gerrit history to router context", e);
      return List.of();
    }
  }

  @VisibleForTesting
  protected List<ChatMessage> buildStoredAutomaticReviewMessages(GerritChange change) {
    if (conversationStore == null || change == null || change.getFullChangeId() == null) {
      return List.of();
    }

    return conversationStore.getAutomaticReviewResponseTexts(change.getFullChangeId()).stream()
        .map(String::trim)
        .filter(message -> !message.isBlank())
        .map(LangChainChatMessages::aiMessage)
        .map(ChatMessage.class::cast)
        .toList();
  }

  @VisibleForTesting
  protected List<ChatMessage> buildAiReviewMessages(
      ChangeSetData changeSetData, GerritChange change) {
    if (gerritClient == null || localizer == null || changeSetData == null || change == null) {
      return List.of();
    }

    try {
      GerritClientData gerritClientData = gerritClient.getClientData(change);
      if (gerritClientData == null) {
        return List.of();
      }
      return aiReviewHistoryCollector
          .collect(config, localizer, changeSetData.getAiAccountId(), gerritClientData)
          .getEntries()
          .stream()
          .filter(entry -> Settings.OPENAI_ROLE_ASSISTANT.equals(entry.getRole()))
          .filter(entry -> !entry.isSystemMessage())
          .map(AiReviewHistoryInfo.Entry::getMessage)
          .map(String::trim)
          .filter(message -> !message.isBlank())
          .map(LangChainChatMessages::aiMessage)
          .map(ChatMessage.class::cast)
          .toList();
    } catch (Exception e) {
      log.debug("Unable to add previous AI reviews to router context", e);
      return List.of();
    }
  }

  private ReviewAssistantStage parseRoute(String route) {
    if (route == null) {
      log.warn("LangChain routing agent returned null route; defaulting to patchset agent");
      return ReviewAssistantStage.REVIEW_CODE;
    }
    String normalized = route.trim().toUpperCase(Locale.ROOT);
    if (normalized.contains("COMMIT_MESSAGE") || normalized.contains("COMMIT MESSAGE")) {
      return ReviewAssistantStage.REVIEW_COMMIT_MESSAGE;
    }
    if (!normalized.contains("PATCHSET")) {
      log.warn(
          "LangChain routing agent returned unrecognized route `{}`; defaulting to patchset agent",
          route);
    }
    return ReviewAssistantStage.REVIEW_CODE;
  }
}
