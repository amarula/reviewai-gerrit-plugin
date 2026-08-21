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

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.isJsonObjectAsString;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.unwrapJsonCode;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai.AiClientBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai.ReviewConcernLedgerOperations;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiHistory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.ProjectInstructionsAppender;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.LangChainMemoryId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.messages.LangChainChatMessages;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.LangChainProviderFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiCostTracker;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class LangChainClient extends AiClientBase implements IAiClient {

  private static final String FORMAT_REPLIES_SCHEMA_RESOURCE = "config/formatRepliesSchema.json";
  private static final String FORMAT_SPECIALIZED_REPLIES_SCHEMA_RESOURCE =
      "config/formatSpecializedRepliesSchema.json";
  private static final String FORMAT_SPECIALIZED_TRIAGE_SCHEMA_RESOURCE =
      "config/formatSpecializedTriageSchema.json";
  private static final String FORMAT_SPECIALIZED_CONSOLIDATION_SCHEMA_RESOURCE =
      "config/formatSpecializedConsolidationSchema.json";
  private static final String FORMAT_SPECIALIZED_HISTORICAL_REPETITION_SCHEMA_RESOURCE =
      "config/formatSpecializedHistoricalRepetitionSchema.json";
  private static final String FORMAT_SPECIALIZED_CONFLICT_RESOLUTION_SCHEMA_RESOURCE =
      "config/formatSpecializedConflictResolutionSchema.json";
  private static final String FORMAT_SPECIALIZED_VERIFICATION_SCHEMA_RESOURCE =
      "config/formatSpecializedVerificationSchema.json";
  private static final List<String> ON_DEMAND_TOOL_RESOURCES =
      List.of("config/treeTool.json", "config/getContentTool.json", "config/grepTool.json");

  private final ICodeContextPolicy codeContextPolicy;
  private final LangChainTokenEstimatorProvider tokenEstimatorProvider;
  private final GerritClient gerritClient;
  protected final GitRepoFiles gitRepoFiles;
  private final ProjectInstructionsAppender projectInstructionsAppender;
  private final Localizer localizer;
  private final PluginDataHandlerProvider pluginDataHandlerProvider;
  private final PluginChatMemoryStore chatMemoryStore;
  protected final ReviewAiMetrics metrics;
  protected final AiCostTracker costTracker;
  // Field exposed only for test usage
  private final ResponseFormat structuredResponseFormat;
  private final ResponseFormat specializedRepliesResponseFormat;
  private final ResponseFormat specializedTriageResponseFormat;
  private final ResponseFormat specializedConsolidationResponseFormat;
  private final ResponseFormat specializedHistoricalRepetitionResponseFormat;
  private final ResponseFormat specializedConflictResolutionResponseFormat;
  private final ResponseFormat specializedVerificationResponseFormat;
  private final List<ToolSpecification> contextTools;
  private final LangChainExecutor toolExecutor;
  private final LangChainConcernReviewer concernReviewer;
  private final LangChainNewIssueFinder newIssueFinder;
  private final LangChainReviewFeedbackClassifier reviewFeedbackClassifier;
  private final LangChainExecutor specializedRepliesToolExecutor;
  private final LangChainExecutor specializedTriageToolExecutor;
  private final LangChainExecutor specializedConsolidationToolExecutor;
  private final LangChainExecutor specializedHistoricalRepetitionToolExecutor;
  private final LangChainExecutor specializedConflictResolutionToolExecutor;
  private final LangChainExecutor specializedVerificationToolExecutor;
  private final ReviewConcernLedgerOperations concernLedgerOperations;
  private final LangChainSingleAgentConcernWorkflow singleAgentConcernWorkflow;

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

  @Getter
  protected static class RawReviewRequestResult {
    private final String responseText;
    private final String requestBody;

    protected RawReviewRequestResult(String responseText, String requestBody) {
      this.responseText = responseText;
      this.requestBody = requestBody;
    }
  }

  protected record ConversationResolution(String conversationId, boolean existingConversation) {}

  protected ConversationResolution conversationResolution(
      String conversationId, boolean existingConversation) {
    return new ConversationResolution(conversationId, existingConversation);
  }

  @Inject
  public LangChainClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      PluginChatMemoryStore chatMemoryStore,
      GitRepoFiles gitRepoFiles,
      ReviewAiMetrics metrics) {
    super(config);
    this.codeContextPolicy = codeContextPolicy;
    this.tokenEstimatorProvider = new LangChainTokenEstimatorProvider(config);
    this.gerritClient = gerritClient;
    this.gitRepoFiles = gitRepoFiles;
    this.projectInstructionsAppender = new ProjectInstructionsAppender(gitRepoFiles);
    this.localizer = localizer;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.chatMemoryStore = chatMemoryStore;
    this.metrics = metrics == null ? new ReviewAiMetrics() : metrics;
    this.costTracker = new AiCostTracker(config, metrics);
    this.structuredResponseFormat =
        new LangChainStructuredResponseFactory(FORMAT_REPLIES_SCHEMA_RESOURCE)
            .loadStructuredResponseFormat();
    this.specializedRepliesResponseFormat =
        new LangChainStructuredResponseFactory(FORMAT_SPECIALIZED_REPLIES_SCHEMA_RESOURCE)
            .loadStructuredResponseFormat();
    this.specializedTriageResponseFormat =
        new LangChainStructuredResponseFactory(FORMAT_SPECIALIZED_TRIAGE_SCHEMA_RESOURCE)
            .loadStructuredResponseFormat();
    this.specializedConsolidationResponseFormat =
        new LangChainStructuredResponseFactory(FORMAT_SPECIALIZED_CONSOLIDATION_SCHEMA_RESOURCE)
            .loadStructuredResponseFormat();
    this.specializedHistoricalRepetitionResponseFormat =
        new LangChainStructuredResponseFactory(
                FORMAT_SPECIALIZED_HISTORICAL_REPETITION_SCHEMA_RESOURCE)
            .loadStructuredResponseFormat();
    this.specializedConflictResolutionResponseFormat =
        new LangChainStructuredResponseFactory(FORMAT_SPECIALIZED_CONFLICT_RESOLUTION_SCHEMA_RESOURCE)
            .loadStructuredResponseFormat();
    this.specializedVerificationResponseFormat =
        new LangChainStructuredResponseFactory(FORMAT_SPECIALIZED_VERIFICATION_SCHEMA_RESOURCE)
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
    this.contextTools = contextTools;
    boolean requireInitialToolUse =
        config != null
            && config.getCodeContextPolicy() == CodeContextPolicies.ON_DEMAND
            && shouldUseOpenAiResponses(config.getAiProviderType());
    ResponseFormat toolExecutorResponseFormat =
        getProviderResponseFormat(config, contextTools, structuredResponseFormat);
    this.toolExecutor =
        new LangChainExecutor(
            config,
            toolExecutorResponseFormat,
            contextTools,
            requireInitialToolUse,
            gitRepoFiles,
            costTracker);
    this.concernReviewer =
        new LangChainConcernReviewer(
            config,
            contextTools,
            requireInitialToolUse,
            gitRepoFiles,
            costTracker,
            responseFormat ->
                getProviderResponseFormat(config, this.contextTools, responseFormat));
    this.newIssueFinder = new LangChainNewIssueFinder(config);
    this.reviewFeedbackClassifier =
        new LangChainReviewFeedbackClassifier(
            config,
            costTracker,
            responseFormat ->
                getProviderResponseFormat(config, List.of(), responseFormat));
    ResponseFormat specializedToolExecutorResponseFormat =
        getProviderResponseFormat(config, contextTools, specializedRepliesResponseFormat);
    this.specializedRepliesToolExecutor =
        new LangChainExecutor(
            config,
            specializedToolExecutorResponseFormat,
            contextTools,
            requireInitialToolUse,
            gitRepoFiles,
            costTracker);
    ResponseFormat specializedTriageToolExecutorResponseFormat =
        getProviderResponseFormat(config, contextTools, specializedTriageResponseFormat);
    this.specializedTriageToolExecutor =
        new LangChainExecutor(
            config,
            specializedTriageToolExecutorResponseFormat,
            contextTools,
            requireInitialToolUse,
            gitRepoFiles,
            costTracker);
    this.specializedConsolidationToolExecutor =
        new LangChainExecutor(
            config,
            getProviderResponseFormat(config, contextTools, specializedConsolidationResponseFormat),
            contextTools,
            requireInitialToolUse,
            gitRepoFiles,
            costTracker);
    this.specializedHistoricalRepetitionToolExecutor =
        new LangChainExecutor(
            config,
            getProviderResponseFormat(
                config, contextTools, specializedHistoricalRepetitionResponseFormat),
            contextTools,
            requireInitialToolUse,
            gitRepoFiles,
            costTracker);
    this.specializedConflictResolutionToolExecutor =
        new LangChainExecutor(
            config,
            getProviderResponseFormat(
                config, contextTools, specializedConflictResolutionResponseFormat),
            contextTools,
            requireInitialToolUse,
            gitRepoFiles,
            costTracker);
    this.specializedVerificationToolExecutor =
        new LangChainExecutor(
            config,
            getProviderResponseFormat(config, contextTools, specializedVerificationResponseFormat),
            contextTools,
            requireInitialToolUse,
            gitRepoFiles,
            costTracker);
    this.concernLedgerOperations = new ReviewConcernLedgerOperations();
    this.singleAgentConcernWorkflow =
        new LangChainSingleAgentConcernWorkflow(
            config,
            concernLedgerOperations,
            this::reviewFeedback,
            (data, change, patchSet) -> toConcernWorkflowResult(
                askSingleRequest(data, change, patchSet)),
            this::reviewConcerns,
            (data, change, concerns, incrementalPatch, fullPatch) ->
                toConcernWorkflowResult(
                    findNewIssueReplies(
                        data, change, concerns, incrementalPatch, fullPatch)));
    log.debug("Initialized LangChainClient");
  }

  protected ReviewConcernLedgerOperations concernLedgerOperations() {
    return concernLedgerOperations;
  }

  @VisibleForTesting
  public LangChainClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider) {
    this(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        pluginDataHandlerProvider,
        null,
        null,
        new ReviewAiMetrics());
  }

  @VisibleForTesting
  public LangChainClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer) {
    this(config, codeContextPolicy, gerritClient, localizer, null, null, null, new ReviewAiMetrics());
  }

  @Override
  public AiResponseContent ask(ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws Exception {
    if (changeSetData.getSuggestMode()) {
      return getSuggestClient().ask(changeSetData, change, patchSet);
    }
    return askReview(changeSetData, change, patchSet);
  }

  protected AiResponseContent askReview(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    if (singleAgentConcernWorkflow.applies(changeSetData, change)) {
      LangChainSingleAgentConcernWorkflow.ReviewResult result =
          singleAgentConcernWorkflow.review(changeSetData, change, patchSet);
      requestBody = result == null ? null : result.requestBody();
      return result == null ? null : result.responseContent();
    }
    ReviewRequestResult reviewRequestResult = askSingleRequest(changeSetData, change, patchSet);
    requestBody = reviewRequestResult == null ? null : reviewRequestResult.getRequestBody();
    return reviewRequestResult == null ? null : reviewRequestResult.getResponseContent();
  }

  private LangChainSingleAgentConcernWorkflow.ReviewResult toConcernWorkflowResult(
      ReviewRequestResult result) {
    return result == null
        ? null
        : new LangChainSingleAgentConcernWorkflow.ReviewResult(
            result.getResponseContent(), result.getRequestBody());
  }

  protected LangChainSuggestClient getSuggestClient() {
    return new LangChainSuggestClient(this);
  }

  @VisibleForTesting
  protected ReviewRequestResult askSingleRequest(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    RawReviewRequestResult rawResult =
        askSingleRawRequestWithFallback(changeSetData, change, patchSet);
    return rawResult == null
        ? null
        : new ReviewRequestResult(
            toResponseContent(rawResult.getResponseText()), rawResult.getRequestBody());
  }

  private RawReviewRequestResult askSingleRawRequestWithFallback(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    RawReviewRequestResult rawResult = askSingleRawRequest(changeSetData, change, patchSet);
    Optional<AiModelRoute> fallbackRoute =
        rawResult == null
            ? Optional.empty()
            : config.resolveMockAiFallbackRoute(rawResult.getResponseText());
    if (fallbackRoute.isPresent()) {
      log.info(
          "Mock AI response requested fallback to provider/model {}",
          fallbackRoute.get().modelRoute());
      rawResult = askSingleRawRequest(changeSetData, change, patchSet, fallbackRoute.get());
    }
    return rawResult;
  }

  protected ReviewerConcerns reviewConcerns(
      ChangeSetData changeSetData,
      GerritChange change,
      ReviewerConcerns existingConcerns,
      String incrementalPatchSet,
      String fullPatchSet)
      throws Exception {
    return concernReviewer.review(
        changeSetData,
        change,
        existingConcerns,
        incrementalPatchSet,
        fullPatchSet,
        (requestData, requestChange, requestPatchSet) -> {
          RawReviewRequestResult rawResult =
              askSingleRawRequestWithFallback(requestData, requestChange, requestPatchSet);
          return rawResult == null ? null : rawResult.getResponseText();
        });
  }

  protected ReviewFeedbackMemory reviewFeedback(
      ChangeSetData changeSetData, GerritChange change) throws Exception {
    ReviewFeedbackMemory currentMemory = changeSetData.getReviewFeedbackMemory();
    boolean hasPendingComments =
        changeSetData.getPendingReviewFeedbackCommentIds() != null
            && !changeSetData.getPendingReviewFeedbackCommentIds().isEmpty();
    if (!shouldClassifyReviewFeedback(changeSetData)
        || (hasPendingComments && gerritClient == null)) {
      return currentMemory;
    }
    ReviewFeedbackMemory feedback =
        reviewFeedbackClassifier.classify(
            changeSetData,
            change,
            gerritClient == null ? null : gerritClient.getClientData(change),
            currentMemory,
            this::askSingleRawResponseTextWithFallback);
    changeSetData.setReviewFeedbackClassified(true);
    return feedback;
  }

  protected boolean shouldClassifyReviewFeedback(ChangeSetData changeSetData) {
    boolean hasPendingComments =
        changeSetData.getPendingReviewFeedbackCommentIds() != null
            && !changeSetData.getPendingReviewFeedbackCommentIds().isEmpty();
    boolean hasConditionLabels =
        changeSetData.getConditionLabels() != null
            && !changeSetData.getConditionLabels().isEmpty();
    return hasPendingComments || hasConditionLabels;
  }

  private String askSingleRawResponseTextWithFallback(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    RawReviewRequestResult result =
        askSingleRawRequestWithFallback(changeSetData, change, patchSet);
    return result == null ? null : result.getResponseText();
  }

  protected ReviewRequestResult findNewIssueReplies(
      ChangeSetData changeSetData,
      GerritChange change,
      ReviewerConcerns reviewedConcerns,
      String incrementalPatchSet,
      String fullPatchSet)
      throws Exception {
    if (incrementalPatchSet == null || incrementalPatchSet.isBlank()) {
      AiResponseContent response = new AiResponseContent("");
      response.setReplies(List.of());
      return new ReviewRequestResult(response, null);
    }
    RawReviewRequestResult rawResult =
        findNewIssuesRaw(
            changeSetData, change, reviewedConcerns, incrementalPatchSet, fullPatchSet);
    return rawResult == null
        ? null
        : new ReviewRequestResult(
            toResponseContent(rawResult.getResponseText()), rawResult.getRequestBody());
  }

  protected RawReviewRequestResult findNewIssuesRaw(
      ChangeSetData changeSetData,
      GerritChange change,
      ReviewerConcerns reviewedConcerns,
      String incrementalPatchSet,
      String fullPatchSet)
      throws Exception {
    return newIssueFinder.find(
        changeSetData,
        change,
        reviewedConcerns,
        incrementalPatchSet,
        fullPatchSet,
        this::askSingleRawRequestWithFallback);
  }

  protected RawReviewRequestResult askSingleRawRequest(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    return askSingleRawRequest(changeSetData, change, patchSet, null);
  }

  @VisibleForTesting
  protected RawReviewRequestResult askSingleRawRequest(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      AiModelRoute aiModelRouteOverride)
      throws Exception {
    if (aiModelRouteOverride == null) {
      return doAskSingleRawRequest(changeSetData, change, patchSet, false);
    }
    return config.withAiModelRoute(
        aiModelRouteOverride, () -> doAskSingleRawRequest(changeSetData, change, patchSet, true));
  }

  @VisibleForTesting
  protected RawReviewRequestResult rawReviewRequestResult(String responseText, String requestBody) {
    return new RawReviewRequestResult(responseText, requestBody);
  }

  private RawReviewRequestResult doAskSingleRawRequest(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      boolean rebuildToolExecutor)
      throws Exception {
    AiProviderType providerType = config.getAiProviderType();
    ReviewAiMetrics.MetricTimer requestTimer =
        metrics.startAiRequest(
            providerType,
            config.getAiModel(),
            changeSetData.getReviewAssistantStage(),
            changeSetData.getSpecializedAgentName());
    try {
      boolean useOpenAiResponses = shouldUseOpenAiResponses(providerType);
      var prompt = AiPromptFactory.getAiPrompt(config, changeSetData, change, codeContextPolicy);
      String systemInstructions =
          projectInstructionsAppender.append(
              prompt, change, prompt.getDefaultAiAssistantInstructions());
      Object memoryId = LangChainMemoryId.from(changeSetData, change);
      boolean forgetThreadRequested = isForgetThreadRequested(changeSetData);
      if (forgetThreadRequested && chatMemoryStore != null) {
        chatMemoryStore.deleteMessages(memoryId);
      }
      ConversationResolution conversationResolution =
          resolveConversation(providerType, changeSetData, change);
      boolean omitRequestContext =
          shouldOmitRequestContext(
              useOpenAiResponses,
              conversationResolution.existingConversation(),
              changeSetData,
              change);
      String userMessage = getUserMessageForRequest(prompt, patchSet, omitRequestContext);

      log.debug("LangChain system instructions for {}: {}", memoryId, systemInstructions);
      log.debug("LangChain user prompt for {}: {}", memoryId, userMessage);

      ChatMemory memory =
          shouldUseOpenAiConversation(providerType)
              ? buildTransientMemory(memoryId)
              : buildMemory(memoryId);
      boolean hasStoredMemory =
          prepareMemoryForRequest(memory, useOpenAiResponses, systemInstructions);

      if (!hasStoredMemory && shouldIncludeInitialHistory(changeSetData)) {
        GerritClientData gerritClientData = gerritClient.getClientData(change);
        AiHistory aiHistory = new AiHistory(config, changeSetData, gerritClientData, localizer);
        List<ChatMessage> history =
            useOpenAiResponses
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

      AiMessage ai =
          (rebuildToolExecutor ? buildToolExecutor(changeSetData) : getToolExecutor(changeSetData))
              .execute(model, change, memory);
      String responseText = ai != null ? ai.text() : null;

      if (responseText == null) {
        log.warn("LangChain model returned null response text");
        requestTimer.empty();
        return null;
      }

      if (ai.hasToolExecutionRequests()) {
        log.warn("Skipping final LangChain memory update because response still has tool requests");
      } else {
        memory.add(ai);
      }

      requestTimer.complete();
      return new RawReviewRequestResult(responseText, userMessage);
    } catch (Exception e) {
      requestTimer.fail();
      log.warn("Error while processing LangChain request", e);
      throw new AiConnectionFailException(e);
    }
  }

  protected boolean shouldIncludeInitialHistory(ChangeSetData changeSetData) {
    return !isForgetThreadRequested(changeSetData);
  }

  protected boolean isForgetThreadRequested(ChangeSetData changeSetData) {
    return changeSetData != null
        && Boolean.TRUE.equals(changeSetData.hasParsedCommand(CommandSet.FORGET_THREAD));
  }

  @VisibleForTesting
  protected boolean prepareMemoryForRequest(
      ChatMemory memory, boolean useOpenAiResponses, String systemInstructions) {
    List<ChatMessage> messages = memory.messages();
    if (useOpenAiResponses) {
      return !messages.isEmpty();
    }
    if (!messages.isEmpty()
        && hasCurrentSystemInstructions(messages.getFirst(), systemInstructions)) {
      return true;
    }
    if (!messages.isEmpty()) {
      log.info(
          "Clearing LangChain memory {} because stored system instructions are stale",
          memory.id());
      memory.clear();
    }
    memory.add(LangChainChatMessages.systemMessage(systemInstructions));
    return false;
  }

  private boolean hasCurrentSystemInstructions(ChatMessage message, String systemInstructions) {
    return message instanceof SystemMessage
        && Objects.equals(LangChainChatMessages.content(message), systemInstructions);
  }

  protected boolean shouldOmitRequestContext(
      AiProviderType providerType,
      boolean existingConversation,
      ChangeSetData changeSetData,
      GerritChange change) {
    return shouldOmitRequestContext(
        shouldUseOpenAiConversation(providerType), existingConversation, changeSetData, change);
  }

  protected boolean shouldOmitRequestContext(
      boolean useOpenAiConversation,
      boolean existingConversation,
      ChangeSetData changeSetData,
      GerritChange change) {
    return useOpenAiConversation
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
    return resolveConversation(providerType, changeSetData, null);
  }

  protected ConversationResolution resolveConversation(
      AiProviderType providerType, ChangeSetData changeSetData, GerritChange change)
      throws AiConnectionFailException {
    if (!shouldUseOpenAiConversation(providerType) || pluginDataHandlerProvider == null) {
      return new ConversationResolution(null, false);
    }
    OpenAiConversation conversation = openAiConversation(changeSetData, change);
    boolean forgetThreadRequested = isForgetThreadRequested(changeSetData);
    if (forgetThreadRequested) {
      conversation.clearCurrentConversation();
    }
    boolean existingConversation =
        !forgetThreadRequested && conversation.hasExistingConversation();
    return new ConversationResolution(
        conversation.resolveConversationId(), existingConversation);
  }

  @VisibleForTesting
  protected OpenAiConversation openAiConversation(ChangeSetData changeSetData, GerritChange change) {
    return new OpenAiConversation(
        config,
        pluginDataHandlerProvider,
        LangChainOpenAiConversationKey.from(changeSetData, change));
  }

  protected boolean hasExistingReviewContext(ChangeSetData changeSetData) {
    return new LangChainReviewContextChecker(
            config, pluginDataHandlerProvider, requireOpenAiScopeForExistingReviewContext())
        .hasExistingReviewContext(changeSetData);
  }

  protected boolean shouldUseOpenAiResponses(AiProviderType providerType) {
    return providerType == AiProviderType.OPENAI;
  }

  protected boolean shouldUseOpenAiConversation(AiProviderType providerType) {
    return shouldUseOpenAiResponses(providerType)
        && (config == null || !config.getAiProviderZdr());
  }

  protected boolean requireOpenAiScopeForExistingReviewContext() {
    return false;
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

  private LangChainExecutor getToolExecutor(ChangeSetData changeSetData) {
    if (changeSetData != null && changeSetData.getReviewAssistantStage() != null) {
      LangChainExecutor collectorExecutor =
          switch (changeSetData.getReviewAssistantStage()) {
            case REVIEW_CONCERNS -> concernReviewer.getToolExecutor();
            case CLASSIFY_REVIEW_FEEDBACK -> reviewFeedbackClassifier.getExecutor();
            case REVIEW_SPECIALIZED_TRIAGE -> specializedTriageToolExecutor;
            case REVIEW_SPECIALIZED_CONSOLIDATION -> specializedConsolidationToolExecutor;
            case REVIEW_SPECIALIZED_HISTORICAL_REPETITION ->
                specializedHistoricalRepetitionToolExecutor;
            case REVIEW_SPECIALIZED_CONFLICT_RESOLUTION ->
                specializedConflictResolutionToolExecutor;
            case REVIEW_SPECIALIZED_VERIFICATION -> specializedVerificationToolExecutor;
            default -> null;
          };
      if (collectorExecutor != null) {
        return collectorExecutor;
      }
    }
    if (changeSetData != null && Boolean.TRUE.equals(changeSetData.getSpecializedAgentReview())) {
      return specializedRepliesToolExecutor;
    }
    return toolExecutor;
  }

  private LangChainExecutor buildToolExecutor(ChangeSetData changeSetData) {
    ResponseFormat responseFormat = structuredResponseFormat;
    if (changeSetData != null && changeSetData.getReviewAssistantStage() != null) {
      responseFormat =
          switch (changeSetData.getReviewAssistantStage()) {
            case REVIEW_CONCERNS -> concernReviewer.getResponseFormat();
            case CLASSIFY_REVIEW_FEEDBACK -> reviewFeedbackClassifier.getResponseFormat();
            case REVIEW_SPECIALIZED_TRIAGE -> specializedTriageResponseFormat;
            case REVIEW_SPECIALIZED_CONSOLIDATION ->
                specializedConsolidationResponseFormat;
            case REVIEW_SPECIALIZED_HISTORICAL_REPETITION ->
                specializedHistoricalRepetitionResponseFormat;
            case REVIEW_SPECIALIZED_CONFLICT_RESOLUTION ->
                specializedConflictResolutionResponseFormat;
            case REVIEW_SPECIALIZED_VERIFICATION -> specializedVerificationResponseFormat;
            default ->
                Boolean.TRUE.equals(changeSetData.getSpecializedAgentReview())
                    ? specializedRepliesResponseFormat
                    : responseFormat;
          };
    }
    boolean requireInitialToolUse =
        config != null
            && config.getCodeContextPolicy() == CodeContextPolicies.ON_DEMAND
            && shouldUseOpenAiResponses(config.getAiProviderType());
    return new LangChainExecutor(
        config,
        getProviderResponseFormat(config, contextTools, responseFormat),
        contextTools,
        requireInitialToolUse,
        gitRepoFiles,
        costTracker);
  }

  private ResponseFormat getProviderResponseFormat(
      Configuration config, List<ToolSpecification> contextTools, ResponseFormat responseFormat) {
    if (config == null) {
      return responseFormat;
    }
    if (config.getAiProviderType() == AiProviderType.DEEPSEEK) {
      return ResponseFormat.builder().type(ResponseFormatType.JSON).build();
    }
    if (config.getAiProviderType() == AiProviderType.GEMINI
        && contextTools != null
        && !contextTools.isEmpty()) {
      return null;
    }
    return responseFormat;
  }

  @Override
  public String getRequestBody() {
    return requestBody;
  }
}
