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
import com.openai.errors.OpenAIServiceException;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai.AiClientBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiHistory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.LangChainMemoryId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.messages.LangChainChatMessages;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.LangChainProviderFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import java.util.List;
import java.util.Locale;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.isJsonObjectAsString;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.unwrapJsonCode;

@Slf4j
@Singleton
public class LangChainClient extends AiClientBase implements IAiClient {

  private static final String FORMAT_REPLIES_SCHEMA_RESOURCE = "config/formatRepliesSchema.json";
  private static final List<String> ON_DEMAND_TOOL_RESOURCES =
      List.of("config/treeTool.json", "config/getContentTool.json", "config/grepTool.json");

  private final ICodeContextPolicy codeContextPolicy;
  private final LangChainTokenEstimatorProvider tokenEstimatorProvider;
  private final GerritClient gerritClient;
  private final Localizer localizer;
  private final PluginDataHandlerProvider pluginDataHandlerProvider;
  private final PluginChatMemoryStore chatMemoryStore;
  // Field exposed only for test usage
  private final ResponseFormat structuredResponseFormat;
  private final LangChainExecutor toolExecutor;

  private String requestBody;

  @Getter
  protected static class ReviewRequestResult {
    private final AiResponseContent responseContent;
    private final String requestBody;

    protected ReviewRequestResult(AiResponseContent responseContent, String requestBody) {
      this.responseContent = responseContent;
      this.requestBody = requestBody;
    }
  }

  protected record ConversationResolution(String conversationId, boolean existingConversation) {}
  protected record ExecutionOutcome(AiMessage aiMessage, ChatMemory memory) {}

  @Inject
  public LangChainClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      PluginChatMemoryStore chatMemoryStore) {
    super(config);
    this.codeContextPolicy = codeContextPolicy;
    this.tokenEstimatorProvider = new LangChainTokenEstimatorProvider(config);
    this.gerritClient = gerritClient;
    this.localizer = localizer;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.chatMemoryStore = chatMemoryStore;
    this.structuredResponseFormat =
        new LangChainStructuredResponseFactory(FORMAT_REPLIES_SCHEMA_RESOURCE)
            .loadStructuredResponseFormat();
    List<ToolSpecification> contextTools = List.of();
    if (config != null && config.getCodeContextPolicy() == CodeContextPolicies.ON_DEMAND) {
      contextTools =
          ON_DEMAND_TOOL_RESOURCES.stream()
              .map(
                  resource ->
                      new LangChainToolSpecificationFactory(resource).loadToolSpecification())
              .filter(toolSpecification -> toolSpecification != null)
              .toList();
    }
    boolean requireInitialToolUse =
        config != null
            && config.getCodeContextPolicy() == CodeContextPolicies.ON_DEMAND
            && config.getAiProviderType() == AiProviderType.OPENAI;
    ResponseFormat toolExecutorResponseFormat =
        supportsStructuredResponseWithTools(config, contextTools)
            ? structuredResponseFormat
            : null;
    this.toolExecutor =
        new LangChainExecutor(
            config, toolExecutorResponseFormat, contextTools, requireInitialToolUse);
    log.debug("Initialized LangChainClient");
  }

  @VisibleForTesting
  public LangChainClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider) {
    this(config, codeContextPolicy, gerritClient, localizer, pluginDataHandlerProvider, null);
  }

  @VisibleForTesting
  public LangChainClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer) {
    this(config, codeContextPolicy, gerritClient, localizer, null, null);
  }

  @Override
  public AiResponseContent ask(ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws Exception {
    ReviewRequestResult reviewRequestResult = askSingleRequest(changeSetData, change, patchSet);
    requestBody = reviewRequestResult == null ? null : reviewRequestResult.getRequestBody();
    return reviewRequestResult == null ? null : reviewRequestResult.getResponseContent();
  }

  @VisibleForTesting
  protected ReviewRequestResult askSingleRequest(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    try {
      AiProviderType providerType = config.getAiProviderType();
      var prompt = AiPromptFactory.getAiPrompt(config, changeSetData, change, codeContextPolicy);
      String systemInstructions = prompt.getDefaultAiAssistantInstructions();
      Object memoryId = LangChainMemoryId.from(changeSetData, change);
      ConversationResolution conversationResolution =
          resolveConversation(providerType, changeSetData);
      boolean omitRequestContext =
          shouldOmitRequestContext(
              providerType, conversationResolution.existingConversation(), changeSetData, change);
      String userMessage =
          getUserMessageForRequest(prompt, patchSet, omitRequestContext);

      log.info("LangChain system instructions for {}: {}", memoryId, systemInstructions);
      log.info("LangChain user prompt for {}: {}", memoryId, userMessage);

      ChatMemory memory =
          createMemory(providerType, conversationResolution.conversationId(), memoryId);
      boolean hasStoredMemory = !memory.messages().isEmpty();

      if (!hasStoredMemory && providerType != AiProviderType.OPENAI) {
        memory.add(LangChainChatMessages.systemMessage(systemInstructions));
      }

      if (!hasStoredMemory) {
        GerritClientData gerritClientData = gerritClient.getClientData(change);
        AiHistory aiHistory = new AiHistory(config, changeSetData, gerritClientData, localizer);
        List<ChatMessage> history =
            providerType == AiProviderType.OPENAI
                ? LangChainChatMessages.buildNonAiDiscussion(aiHistory, gerritClientData, change)
                : LangChainChatMessages.build(aiHistory, gerritClientData, change);
        for (ChatMessage message : history) {
          memory.add(message);
        }
      }

      memory.add(LangChainChatMessages.userMessage(userMessage));

      double temperature =
          change.getIsCommentEvent()
              ? Double.parseDouble(config.getAiCommentTemperature())
              : Double.parseDouble(config.getAiReviewTemperature());

      ILangChainProvider provider = LangChainProviderFactory.get(providerType);
      LangChainProvider providerModel =
          provider.buildChatModel(
              config, temperature, conversationResolution.conversationId(), systemInstructions);
      ChatModel model = providerModel.getModel();

      log.info(
          "LangChain request for {} using provider {} model {} (temperature={}, endpoint={})",
          memoryId,
          providerType,
          config.getAiModel(),
          temperature,
          providerModel.getEndpoint());

      List<ChatMessage> memorySnapshot = memory.messages();
      log.debug(
          "LangChain memory prepared for {} with {} messages: {}",
          memoryId,
          memorySnapshot.size(),
          memorySnapshot);

      ExecutionOutcome executionOutcome =
          executeWithConversationFallback(
              providerType,
              conversationResolution.conversationId(),
              changeSetData,
              change,
              memory,
              provider,
              temperature,
              systemInstructions,
              memoryId,
              model,
              providerModel.getEndpoint());
      ChatMemory memoryForResponse = executionOutcome.memory();
      AiMessage ai = executionOutcome.aiMessage();
      String responseText = ai != null ? ai.text() : null;

      if (responseText == null) {
        log.warn("LangChain model returned null response text");
        return null;
      }

      if (ai.hasToolExecutionRequests()) {
        log.warn("Skipping final LangChain memory update because response still has tool requests");
      } else {
        memoryForResponse.add(ai);
      }

      return new ReviewRequestResult(toResponseContent(responseText), userMessage);
    } catch (Exception e) {
      log.warn("Error while processing LangChain request", e);
      throw new AiConnectionFailException(e);
    }
  }

  protected boolean shouldOmitRequestContext(
      AiProviderType providerType,
      boolean existingConversation,
      ChangeSetData changeSetData,
      GerritChange change) {
    return providerType == AiProviderType.OPENAI
        && existingConversation
        && change.getIsCommentEvent()
        && !changeSetData.getForcedReview();
  }

  protected void setRequestBody(String requestBody) {
    this.requestBody = requestBody;
  }

  @VisibleForTesting
  protected AiResponseContent toResponseContent(String responseText) {
    if (isJsonObjectAsString(responseText)) {
      return convertResponseContentFromJson(unwrapJsonCode(responseText));
    }
    return new AiResponseContent(responseText);
  }

  protected String resolveConversationId(AiProviderType providerType, ChangeSetData changeSetData)
      throws AiConnectionFailException {
    return resolveConversation(providerType, changeSetData).conversationId();
  }

  protected ConversationResolution resolveConversation(
      AiProviderType providerType, ChangeSetData changeSetData) throws AiConnectionFailException {
    if (providerType != AiProviderType.OPENAI || pluginDataHandlerProvider == null) {
      return new ConversationResolution(null, false);
    }
    String conversationKey = getConversationStorageKey(changeSetData);
    OpenAiConversation conversation =
        new OpenAiConversation(config, pluginDataHandlerProvider, conversationKey);
    boolean existingConversation = conversation.hasExistingConversation();
    return new ConversationResolution(
        conversation.resolveConversationId(), existingConversation);
  }

  protected String getConversationStorageKey(ChangeSetData changeSetData) {
    String conversationKey = OpenAiConversation.KEY_CONVERSATION_ID;
    if (changeSetData.getReviewAssistantStage() == null) {
      return conversationKey;
    }
    switch (changeSetData.getReviewAssistantStage()) {
      case REVIEW_CODE:
      case REVIEW_COMMIT_MESSAGE:
        return OpenAiConversation.getMultiAgentConversationKey(
            changeSetData.getReviewAssistantStage());
      default:
        return conversationKey;
    }
  }

  protected ExecutionOutcome executeWithConversationFallback(
      AiProviderType providerType,
      String conversationId,
      ChangeSetData changeSetData,
      GerritChange change,
      ChatMemory memory,
      ILangChainProvider provider,
      double temperature,
      String systemInstructions,
      Object memoryId,
      ChatModel model,
      String endpoint) {
    try {
      return new ExecutionOutcome(toolExecutor.execute(model, change, memory), memory);
    } catch (RuntimeException e) {
      if (!shouldRetryWithoutConversation(providerType, conversationId, e)) {
        throw e;
      }
      log.warn(
          "OpenAI rejected conversation parameter for {} (conversationId={}); retrying once "
              + "without conversation and persisting local memory mode for this Change Set",
          memoryId,
          conversationId,
          e);
      clearResolvedConversationId(changeSetData);
      LangChainProvider retryProviderModel =
          provider.buildChatModel(config, temperature, null, systemInstructions);
      ChatMemory retryMemory = createMemory(providerType, null, memoryId);
      if (retryMemory.messages().isEmpty()) {
        for (ChatMessage message : memory.messages()) {
          retryMemory.add(message);
        }
      }
      log.info(
          "Retrying LangChain request for {} using provider {} model {} "
              + "(temperature={}, endpoint={})",
          memoryId,
          providerType,
          config.getAiModel(),
          temperature,
          retryProviderModel.getEndpoint() == null ? endpoint : retryProviderModel.getEndpoint());
      return new ExecutionOutcome(
          toolExecutor.execute(retryProviderModel.getModel(), change, retryMemory), retryMemory);
    }
  }

  protected boolean shouldRetryWithoutConversation(
      AiProviderType providerType, String conversationId, Throwable throwable) {
    if (providerType != AiProviderType.OPENAI
        || conversationId == null
        || conversationId.isBlank()
        || throwable == null) {
      return false;
    }
    OpenAIServiceException openAiException = findOpenAiServiceException(throwable);
    if (openAiException != null) {
      String param = openAiException.param().orElse("");
      String code = openAiException.code().orElse("");
      String body = String.valueOf(openAiException.body()).toLowerCase(Locale.ROOT);
      if ("conversation".equalsIgnoreCase(param)
          && ("unsupported_parameter".equalsIgnoreCase(code)
              || body.contains("zero data retention"))) {
        return true;
      }
    }
    String message = flattenMessages(throwable).toLowerCase(Locale.ROOT);
    return message.contains("param=conversation")
        && (message.contains("unsupported_parameter")
            || message.contains("zero data retention"));
  }

  protected void clearResolvedConversationId(ChangeSetData changeSetData) {
    if (pluginDataHandlerProvider == null) {
      return;
    }
    OpenAiConversation conversation =
        new OpenAiConversation(
            config, pluginDataHandlerProvider, getConversationStorageKey(changeSetData));
    try {
      conversation.setConversationDisabled(true);
      pluginDataHandlerProvider
          .getChangeScope()
          .removeValue(getConversationStorageKey(changeSetData));
    } catch (RuntimeException e) {
      log.warn(
          "Failed to switch OpenAI conversation mode to local memory for key {}",
          getConversationStorageKey(changeSetData),
          e);
    }
  }

  protected ChatMemory createMemory(
      AiProviderType providerType, String conversationId, Object memoryId) {
    if (providerType != AiProviderType.OPENAI) {
      return buildMemory(memoryId);
    }
    if (conversationId == null || conversationId.isBlank()) {
      return buildMemory(memoryId);
    }
    return buildTransientMemory(memoryId);
  }

  private OpenAIServiceException findOpenAiServiceException(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof OpenAIServiceException serviceException) {
        return serviceException;
      }
      current = current.getCause();
    }
    return null;
  }

  private String flattenMessages(Throwable throwable) {
    StringBuilder builder = new StringBuilder();
    Throwable current = throwable;
    while (current != null) {
      String message = current.getMessage();
      if (message != null && !message.isBlank()) {
        if (!builder.isEmpty()) {
          builder.append(" | ");
        }
        builder.append(message);
      }
      current = current.getCause();
    }
    return builder.toString();
  }

  @VisibleForTesting
  protected String getUserMessageForRequest(
      IAiPrompt prompt, String patchSet, boolean omitContext) {
    if (!omitContext) {
      return prompt.getDefaultAiThreadReviewMessage(patchSet);
    }

    String requestDataPrompt = prompt.getAiRequestDataPrompt();
    if (requestDataPrompt != null && !requestDataPrompt.isEmpty()) {
      return requestDataPrompt;
    }
    return prompt.getDefaultAiThreadReviewMessage("");
  }

  protected ChatMemory buildMemory(Object memoryId) {
    TokenWindowChatMemory.Builder builder =
        TokenWindowChatMemory.builder()
            .id(memoryId)
            .maxTokens(config.getAiMaxMemoryTokens(), tokenEstimatorProvider.get());
    if (chatMemoryStore != null) {
      builder.chatMemoryStore(chatMemoryStore);
    }
    return builder.build();
  }

  protected ChatMemory buildTransientMemory(Object memoryId) {
    return TokenWindowChatMemory.builder()
        .id(memoryId)
        .maxTokens(config.getAiMaxMemoryTokens(), tokenEstimatorProvider.get())
        .build();
  }

  private boolean supportsStructuredResponseWithTools(
      Configuration config, List<ToolSpecification> contextTools) {
    return config == null
        || config.getAiProviderType() != AiProviderType.GEMINI
        || contextTools == null
        || contextTools.isEmpty();
  }

  @Override
  public String getRequestBody() {
    return requestBody;
  }
}
