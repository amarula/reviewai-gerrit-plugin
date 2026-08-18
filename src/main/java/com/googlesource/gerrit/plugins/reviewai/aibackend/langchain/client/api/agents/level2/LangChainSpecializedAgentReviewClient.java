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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.ReviewAiExecutors;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiHistory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptSections;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinition;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinitions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainSuggestClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level1.LangChainMultiAgentReviewClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2.SpecializedReviewConcernLedgerOperations.AgentFollowUp;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.messages.LangChainChatMessages;
import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.web.ReviewAgentConversationStore;
import dev.langchain4j.data.message.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.unwrapJsonCode;

@Slf4j
@Singleton
public class LangChainSpecializedAgentReviewClient extends LangChainMultiAgentReviewClient {
  private static final String COMMIT_MESSAGE_AGENT = "COMMIT_MESSAGE";
  private static final ReviewAssistantStage CONSOLIDATION_STAGE =
      ReviewAssistantStage.REVIEW_SPECIALIZED_CONSOLIDATION;
  private static final ReviewAssistantStage HISTORICAL_REPETITION_STAGE =
      ReviewAssistantStage.REVIEW_SPECIALIZED_HISTORICAL_REPETITION;
  private static final ReviewAssistantStage CONFLICT_RESOLUTION_STAGE =
      ReviewAssistantStage.REVIEW_SPECIALIZED_CONFLICT_RESOLUTION;
  private static final ReviewAssistantStage VERIFICATION_STAGE =
      ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION;

  private final SpecializedReviewStageExecutor stageExecutor;
  private final SpecializedReviewPastCommentsCollector pastCommentsCollector;
  private final SpecializedReviewConcernLedgerOperations specializedConcernLedgerOperations;
  private final ICodeContextPolicy codeContextPolicy;
  private final GerritClient gerritClient;
  private final Localizer localizer;
  private final PluginDataHandlerProvider pluginDataHandlerProvider;
  private final PluginChatMemoryStore chatMemoryStore;

  @Inject
  public LangChainSpecializedAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      ReviewAgentConversationStore conversationStore,
      PluginChatMemoryStore chatMemoryStore,
      GitRepoFiles gitRepoFiles,
      ReviewAiExecutors reviewAiExecutors,
      ReviewAiMetrics metrics) {
    this(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        pluginDataHandlerProvider,
        conversationStore,
        chatMemoryStore,
        reviewAiExecutors.getAgentExecutor(),
        gitRepoFiles,
        metrics);
  }

  @VisibleForTesting
  public LangChainSpecializedAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      Executor executor) {
    this(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        null,
        null,
        null,
        executor,
        null,
        new ReviewAiMetrics());
  }

  @VisibleForTesting
  public LangChainSpecializedAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      ReviewAgentConversationStore conversationStore,
      PluginChatMemoryStore chatMemoryStore,
      Executor executor,
      GitRepoFiles gitRepoFiles,
      ReviewAiMetrics metrics) {
    super(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        pluginDataHandlerProvider,
        conversationStore,
        chatMemoryStore,
        executor,
        gitRepoFiles,
        metrics);
    this.stageExecutor = new SpecializedReviewStageExecutor(executor);
    this.pastCommentsCollector =
        new SpecializedReviewPastCommentsCollector(config, gerritClient, localizer);
    this.specializedConcernLedgerOperations =
        new SpecializedReviewConcernLedgerOperations(concernLedgerOperations());
    this.codeContextPolicy = codeContextPolicy;
    this.gerritClient = gerritClient;
    this.localizer = localizer;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.chatMemoryStore = chatMemoryStore;
  }

  @Override
  protected LangChainSuggestClient getSuggestClient() {
    LangChainClient reviewClient =
        new LangChainClient(
            config,
            codeContextPolicy,
            gerritClient,
            localizer,
            pluginDataHandlerProvider,
            chatMemoryStore,
            gitRepoFiles,
            metrics);
    LangChainClient suggestContextClient =
        new SpecializedSuggestLangChainClient(
            config,
            codeContextPolicy,
            gerritClient,
            localizer,
            pluginDataHandlerProvider,
            chatMemoryStore,
            gitRepoFiles,
            metrics);
    return new LangChainSpecializedSuggestClient(
        reviewClient,
        suggestContextClient,
        new SpecializedSuggestReviewContext(config));
  }

  @Override
  protected AiResponseContent askReview(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    if (change.getIsCommentEvent() && !changeSetData.getForcedReview()) {
      ReviewRequestResult reviewRequestResult = askSingleRequest(changeSetData, change, patchSet);
      setRequestBody(reviewRequestResult == null ? null : reviewRequestResult.getRequestBody());
      return reviewRequestResult == null ? null : reviewRequestResult.getResponseContent();
    }
    if (changeSetData.getForcedStagedReview()) {
      if (hasPendingReviewFeedback(changeSetData)) {
        changeSetData.setReviewFeedbackMemory(reviewFeedback(changeSetData, change));
      }
      return super.askReview(changeSetData, change, patchSet);
    }

    CompletableFuture<ReviewFeedbackMemory> feedbackFuture = null;
    if (hasPendingReviewFeedback(changeSetData)) {
      feedbackFuture = stageExecutor.supplyAsync(() -> reviewFeedback(changeSetData, change));
    }
    CompletableFuture<SpecializedReviewTriage> triageFuture =
        stageExecutor.supplyAsync(() -> askTriage(changeSetData, change, patchSet));
    SpecializedReviewTriage triage = stageExecutor.join(triageFuture);
    if (feedbackFuture != null) {
      changeSetData.setReviewFeedbackMemory(stageExecutor.join(feedbackFuture));
    }
    ReviewConcernLedger previousLedger = changeSetData.getPreviousReviewConcernLedger();
    List<SpecializedReviewTriage.AgentPlan> enabledPlans =
        SpecializedReviewConcernPlanSelector.select(
            enabledPlans(changeSetData, triage),
            triage,
            previousLedger,
            agent -> agentInScope(changeSetData, agent));
    if (enabledPlans.isEmpty()) {
      AiResponseContent response = new AiResponseContent("");
      response.setReplies(List.of());
      concernLedgerOperations()
          .attachPendingLedger(
              response,
              change,
              previousLedger == null ? new ReviewConcernLedger() : previousLedger);
      return response;
    }

    if (previousLedger == null) {
      List<SpecializedReviewFindings.AgentFindings> specializedFindings =
          askSpecializedAgents(changeSetData, change, patchSet, enabledPlans);
      SpecializedReviewConcernIds.assignRawConcernIds(specializedFindings);
      CollectorResult collector =
          askCollectorResult(
              changeSetData,
              change,
              patchSet,
              specializedFindings,
              triage.getConsolidationContext(),
              true);
      AiResponseContent response =
          specializedConcernLedgerOperations.nonNullResponse(collector.response());
      concernLedgerOperations()
          .attachPendingLedger(
              response,
              change,
              specializedConcernLedgerOperations.verifiedUpdates(
                  response, collector.verificationCandidates(), specializedFindings));
      return response;
    }

    List<AgentFollowUp> followUps =
        askSpecializedAgentFollowUps(
            changeSetData, change, patchSet, enabledPlans, previousLedger);
    List<SpecializedReviewFindings.AgentFindings> specializedFindings =
        followUps.stream().map(AgentFollowUp::findings).toList();
    SpecializedReviewConcernIds.assignRawConcernIds(specializedFindings);
    CollectorResult collector =
        askCollectorResult(
            changeSetData,
            change,
            patchSet,
            specializedFindings,
            triage.getConsolidationContext(),
            false);
    return specializedConcernLedgerOperations.completeFollowUp(
        specializedConcernLedgerOperations.nonNullResponse(collector.response()),
        change,
        previousLedger,
        followUps,
        specializedConcernLedgerOperations.verifiedUpdates(
            collector.response(), collector.verificationCandidates(), specializedFindings));
  }

  private boolean hasPendingReviewFeedback(ChangeSetData changeSetData) {
    return changeSetData.getPendingReviewFeedbackCommentIds() != null
        && !changeSetData.getPendingReviewFeedbackCommentIds().isEmpty();
  }

  @Override
  protected boolean shouldIncludeInitialHistory(ChangeSetData changeSetData) {
    return super.shouldIncludeInitialHistory(changeSetData)
        && changeSetData.getReviewAssistantStage() != ReviewAssistantStage.REVIEW_SPECIALIZED_TRIAGE
        && !isSpecializedAgentStage(changeSetData.getReviewAssistantStage());
  }

  SpecializedReviewTriage askTriage(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    ChangeSetData triageData =
        SpecializedReviewStageData.staged(
            changeSetData, ReviewAssistantStage.REVIEW_SPECIALIZED_TRIAGE);
    RawReviewRequestResult result =
        askSingleRawRequest(triageData, change, buildTriageInput(changeSetData, change, patchSet));
    setRequestBody(result == null ? null : result.getRequestBody());
    return result == null
        ? new SpecializedReviewTriage()
        : parseTriageResponse(result.getResponseText());
  }

  @VisibleForTesting
  SpecializedReviewTriage parseTriageResponse(String responseText) {
    String unwrappedResponse = unwrapJsonCode(responseText);
    SpecializedReviewTriage triage =
        getGson().fromJson(unwrappedResponse, SpecializedReviewTriage.class);
    if (hasAgentPlans(triage)) {
      return triage;
    }

    AiResponseContent wrappedResponse =
        getGson().fromJson(unwrappedResponse, AiResponseContent.class);
    if (wrappedResponse == null || wrappedResponse.getReplies() == null) {
      return new SpecializedReviewTriage();
    }
    for (AiReplyItem reply : wrappedResponse.getReplies()) {
      if (reply == null || reply.getReply() == null || reply.getReply().isBlank()) {
        continue;
      }
      SpecializedReviewTriage wrappedTriage =
          getGson().fromJson(unwrapJsonCode(reply.getReply()), SpecializedReviewTriage.class);
      if (hasAgentPlans(wrappedTriage)) {
        return wrappedTriage;
      }
    }
    return new SpecializedReviewTriage();
  }

  private boolean hasAgentPlans(SpecializedReviewTriage triage) {
    return triage != null && triage.getAgents() != null && !triage.getAgents().isEmpty();
  }

  SpecializedReviewFindings askSpecializedAgent(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      SpecializedReviewTriage.AgentPlan plan)
      throws Exception {
    ChangeSetData agentData = specializedAgentData(changeSetData, plan);
    if (agentData == null) {
      return null;
    }
    RawReviewRequestResult result =
        askSingleRawRequestWithFallback(agentData, change, buildSpecializedInput(patchSet, plan));
    return result == null
        ? SpecializedReviewFindings.empty()
        : parseFindingsResponse(result.getResponseText());
  }

  private ChangeSetData specializedAgentData(
      ChangeSetData changeSetData, SpecializedReviewTriage.AgentPlan plan) {
    ChangeSetData agentData = changeSetData.copy();
    if (isCommitMessageAgent(plan.getAgent())) {
      agentData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    } else {
      Optional<SpecializedReviewAgentDefinition> definition =
          SpecializedReviewAgentDefinitions.findByName(plan.getAgent());
      if (definition.isEmpty()) {
        log.warn("Skipping unknown specialized review agent {}", plan.getAgent());
        return null;
      }
      SpecializedReviewAgentDefinition agentDefinition = definition.get();
      agentData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT);
      agentData.setSpecializedAgentName(agentDefinition.normalizedName());
      agentData.setSpecializedAgentDescription(agentDefinition.getShortDescription());
      agentData.setSpecializedAgentInstructions(agentDefinition.getInstructions());
    }
    agentData.setForcedStagedReview(true);
    agentData.setSpecializedAgentReview(true);
    agentData.setSpecializedAgentCustomInstructions(plan.getCustomInstructions());
    return agentData;
  }

  AiResponseContent askCollector(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      List<SpecializedReviewFindings.AgentFindings> specializedFindings)
      throws Exception {
    return askCollector(changeSetData, change, patchSet, specializedFindings, null);
  }

  AiResponseContent askCollector(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      List<SpecializedReviewFindings.AgentFindings> specializedFindings,
      String triageContext)
      throws Exception {
    return askCollectorResult(
            changeSetData, change, patchSet, specializedFindings, triageContext)
        .response();
  }

  protected CollectorResult askCollectorResult(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      List<SpecializedReviewFindings.AgentFindings> specializedFindings,
      String triageContext)
      throws Exception {
    return askCollectorResult(
        changeSetData,
        change,
        patchSet,
        specializedFindings,
        triageContext,
        true);
  }

  protected CollectorResult askCollectorResult(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      List<SpecializedReviewFindings.AgentFindings> specializedFindings,
      String triageContext,
      boolean includeHistoricalRepetition)
      throws Exception {
    SpecializedReviewConcernIds.assignRawConcernIds(specializedFindings);
    Set<String> expectedConcernIds = SpecializedReviewConcernIds.rawConcernIds(specializedFindings);
    CompletableFuture<SpecializedReviewFindings> consolidationFuture =
        stageExecutor.supplyAsync(
            () ->
                askFindingsStage(
                    changeSetData,
                    change,
                    buildConsolidationInput(specializedFindings, triageContext),
                    CONSOLIDATION_STAGE));
    CompletableFuture<SpecializedReviewFindings.HistoricalRepetitionResult>
        historicalRepetitionFuture = null;
    if (includeHistoricalRepetition) {
      historicalRepetitionFuture =
          stageExecutor.supplyAsync(
              () ->
                  askHistoricalRepetitionStage(
                      changeSetData,
                      change,
                      buildHistoricalRepetitionInput(changeSetData, change, specializedFindings),
                      expectedConcernIds));
    }
    SpecializedReviewFindings consolidatedFindings = stageExecutor.join(consolidationFuture);
    consolidatedFindings =
        currentRunConsolidationOrFallback(
            consolidatedFindings, specializedFindings, expectedConcernIds);
    SpecializedReviewFindings annotatedFindings;
    if (historicalRepetitionFuture == null) {
      annotatedFindings =
          SpecializedReviewRepetitionMerger.clearRepeatedAnnotations(consolidatedFindings);
    } else {
      annotatedFindings =
          applyHistoricalRepetition(
              consolidatedFindings, stageExecutor.join(historicalRepetitionFuture));
    }
    SpecializedReviewFindings conflictResolvedFindings =
        askFindingsStage(
            changeSetData,
            change,
            buildConflictResolutionInput(annotatedFindings),
            CONFLICT_RESOLUTION_STAGE);
    conflictResolvedFindings =
        currentRunConflictResolutionOrFallback(conflictResolvedFindings, annotatedFindings);
    copyRepeatedAnnotations(conflictResolvedFindings, annotatedFindings);
    VerificationStageResult verification =
        askVerificationStages(changeSetData, change, patchSet, conflictResolvedFindings);
    AiResponseContent response = verification.response();
    inheritRepeatedAnnotations(response, conflictResolvedFindings);
    if (!includeHistoricalRepetition) {
      SpecializedReviewRepetitionMerger.clearRepeatedAnnotations(response);
    }
    setRequestBody(verification.requestBody());
    return new CollectorResult(response, conflictResolvedFindings);
  }

  private VerificationStageResult askVerificationStages(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      SpecializedReviewFindings conflictResolvedFindings)
      throws Exception {
    List<SpecializedReviewTopicVerification.TopicVerificationPatch> topicPatches =
        SpecializedReviewTopicVerification.topicVerificationPatches(patchSet);
    if (topicPatches.size() < 2) {
      String verificationInput = buildVerificationInput(patchSet, conflictResolvedFindings);
      ChangeSetData verificationData =
          SpecializedReviewStageData.staged(changeSetData, VERIFICATION_STAGE);
      return new VerificationStageResult(
          askVerificationStage(verificationData, change, verificationInput), verificationInput);
    }

    List<CompletableFuture<VerificationStageResult>> futures = new ArrayList<>();
    for (SpecializedReviewTopicVerification.TopicVerificationPatch topicPatch : topicPatches) {
      SpecializedReviewFindings findings =
          SpecializedReviewTopicVerification.findingsForTopicPrefix(
              conflictResolvedFindings, topicPatch.prefix());
      String verificationInput = buildVerificationInput(topicPatch.patchSet(), findings);
      ChangeSetData verificationData =
          SpecializedReviewStageData.staged(
              changeSetData,
              VERIFICATION_STAGE,
              SpecializedReviewTopicVerification.verificationConversationSuffix(topicPatch));
      futures.add(
          stageExecutor.supplyAsync(
              () ->
                  new VerificationStageResult(
                      askVerificationStage(verificationData, change, verificationInput),
                      verificationInput)));
    }

    List<AiResponseContent> responses = new ArrayList<>();
    List<String> requestBodies = new ArrayList<>();
    for (CompletableFuture<VerificationStageResult> future : futures) {
      VerificationStageResult result = stageExecutor.join(future);
      responses.add(result.response());
      requestBodies.add(result.requestBody());
    }
    return new VerificationStageResult(
        SpecializedReviewTopicVerification.combinedVerificationResponse(responses),
        String.join("\n\n", requestBodies));
  }

  @VisibleForTesting
  SpecializedReviewFindings.HistoricalRepetitionResult askHistoricalRepetitionStage(
      ChangeSetData changeSetData,
      GerritChange change,
      String input,
      Set<String> expectedConcernIds)
      throws Exception {
    ChangeSetData repetitionData =
        SpecializedReviewStageData.staged(changeSetData, HISTORICAL_REPETITION_STAGE);
    RawReviewRequestResult result = askSingleRawRequestWithFallback(repetitionData, change, input);
    if (result == null || result.getResponseText() == null) {
      throw new IllegalStateException("No response from " + HISTORICAL_REPETITION_STAGE);
    }
    SpecializedReviewFindings.HistoricalRepetitionResult repetitionResult =
        parseHistoricalRepetitionResponse(result.getResponseText());
    if (config != null && config.isSelectedMockAiModelRoute()) {
      return SpecializedReviewPayloads.currentRunHistoricalRepetitionOrFallback(
          repetitionResult, expectedConcernIds);
    }
    SpecializedReviewPayloads.validateHistoricalRepetitionResult(
        repetitionResult, expectedConcernIds);
    return repetitionResult;
  }

  @VisibleForTesting
  SpecializedReviewFindings askFindingsStage(
      ChangeSetData changeSetData,
      GerritChange change,
      String input,
      ReviewAssistantStage stage)
      throws Exception {
    ChangeSetData collectorData = SpecializedReviewStageData.staged(changeSetData, stage);
    RawReviewRequestResult result = askSingleRawRequestWithFallback(collectorData, change, input);
    if (result == null || result.getResponseText() == null) {
      throw new IllegalStateException("No response from " + stage);
    }
    return parseFindingsResponse(result.getResponseText());
  }

  @VisibleForTesting
  AiResponseContent askVerificationStage(
      ChangeSetData changeSetData, GerritChange change, String input) throws Exception {
    ReviewRequestResult result = askSingleRequest(changeSetData, change, input);
    if (result == null || result.getResponseContent() == null) {
      throw new IllegalStateException("No response from " + VERIFICATION_STAGE);
    }
    return result.getResponseContent();
  }

  @VisibleForTesting
  String buildConsolidationInput(
      List<SpecializedReviewFindings.AgentFindings> specializedFindings, String triageContext) {
    return SpecializedReviewPayloads.buildConsolidationInput(specializedFindings, triageContext);
  }

  @VisibleForTesting
  String buildHistoricalRepetitionInput(
      ChangeSetData changeSetData,
      GerritChange change,
      List<SpecializedReviewFindings.AgentFindings> specializedFindings) {
    return SpecializedReviewPayloads.buildHistoricalRepetitionInput(
        specializedFindings, collectPastReviewComments(changeSetData, change));
  }

  @VisibleForTesting
  String buildConflictResolutionInput(SpecializedReviewFindings consolidatedFindings) {
    return SpecializedReviewPayloads.buildConflictResolutionInput(consolidatedFindings);
  }

  @VisibleForTesting
  String buildVerificationInput(String patchSet, SpecializedReviewFindings findings) {
    return SpecializedReviewPayloads.buildVerificationInput(patchSet, findings);
  }

  private List<SpecializedReviewTriage.AgentPlan> enabledPlans(
      ChangeSetData changeSetData, SpecializedReviewTriage triage) {
    if (triage == null || triage.getAgents() == null) {
      return List.of();
    }
    return triage.getAgents().stream()
        .filter(SpecializedReviewTriage.AgentPlan::isEnabled)
        .filter(plan -> plan.getAgent() != null)
        .filter(plan -> agentInScope(changeSetData, plan.getAgent()))
        .toList();
  }

  private boolean agentInScope(ChangeSetData changeSetData, String agent) {
    ReviewScope scope = changeSetData.getReviewScope();
    if (isCommitMessageAgent(agent)) {
      return config.getAiReviewCommitMessages() && scope != ReviewScope.PATCHSET;
    }
    return config.getAiReviewPatchSet()
        && scope != ReviewScope.COMMIT_MESSAGE
        && SpecializedReviewAgentDefinitions.findByName(agent).isPresent();
  }

  private List<SpecializedReviewFindings.AgentFindings> askSpecializedAgents(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      List<SpecializedReviewTriage.AgentPlan> enabledPlans)
      throws Exception {
    List<CompletableFuture<SpecializedReviewFindings.AgentFindings>> futures = new ArrayList<>();
    for (SpecializedReviewTriage.AgentPlan plan : enabledPlans) {
      futures.add(
          stageExecutor.supplyAsync(
              () -> {
                SpecializedReviewFindings findings =
                    askSpecializedAgent(changeSetData, change, patchSet, plan);
                return SpecializedReviewFindings.AgentFindings.from(
                    normalizedAgentName(plan.getAgent()),
                    findings == null ? SpecializedReviewFindings.empty() : findings);
              }));
    }

    List<SpecializedReviewFindings.AgentFindings> replies = new ArrayList<>();
    for (CompletableFuture<SpecializedReviewFindings.AgentFindings> future : futures) {
      replies.add(stageExecutor.join(future));
    }
    return replies;
  }

  private List<AgentFollowUp> askSpecializedAgentFollowUps(
      ChangeSetData changeSetData,
      GerritChange change,
      String fullPatchSet,
      List<SpecializedReviewTriage.AgentPlan> plans,
      ReviewConcernLedger previousLedger)
      throws Exception {
    List<CompletableFuture<AgentFollowUp>> futures = new ArrayList<>();
    for (SpecializedReviewTriage.AgentPlan plan : plans) {
      futures.add(
          stageExecutor.supplyAsync(
              () ->
                  askSpecializedAgentFollowUp(
                      changeSetData, change, fullPatchSet, plan, previousLedger)));
    }

    List<AgentFollowUp> followUps = new ArrayList<>();
    for (CompletableFuture<AgentFollowUp> future : futures) {
      followUps.add(stageExecutor.join(future));
    }
    return followUps;
  }

  protected AgentFollowUp askSpecializedAgentFollowUp(
      ChangeSetData changeSetData,
      GerritChange change,
      String fullPatchSet,
      SpecializedReviewTriage.AgentPlan plan,
      ReviewConcernLedger previousLedger)
      throws Exception {
    String agent = normalizedAgentName(plan.getAgent());
    ChangeSetData agentData = specializedAgentData(changeSetData, plan);
    if (agentData == null) {
      throw new IllegalArgumentException("Unknown specialized review agent " + plan.getAgent());
    }
    ConcernReviewerId reviewer =
        new ConcernReviewerId(ConcernReviewerId.Kind.SPECIALIZED_AGENT, agent);
    ReviewerConcerns reviewedConcerns =
        reviewConcerns(
            agentData,
            change,
            concernLedgerOperations().reviewerConcerns(previousLedger, reviewer),
            changeSetData.getIncrementalPatchSet(),
            fullPatchSet);
    RawReviewRequestResult result =
        findNewIssuesRaw(
            agentData,
            change,
            reviewedConcerns,
            changeSetData.getIncrementalPatchSet(),
            fullPatchSet);
    SpecializedReviewFindings findings =
        result == null
            ? SpecializedReviewFindings.empty()
            : parseFindingsResponse(result.getResponseText());
    return new AgentFollowUp(
        SpecializedReviewFindings.AgentFindings.from(agent, findings), reviewedConcerns);
  }

  @VisibleForTesting
  SpecializedReviewFindings parseFindingsResponse(String responseText) {
    return SpecializedReviewPayloads.parseFindingsResponse(responseText);
  }

  @VisibleForTesting
  SpecializedReviewFindings.HistoricalRepetitionResult parseHistoricalRepetitionResponse(
      String responseText) {
    return SpecializedReviewPayloads.parseHistoricalRepetitionResponse(responseText);
  }

  @VisibleForTesting
  SpecializedReviewFindings currentRunConsolidationOrFallback(
      SpecializedReviewFindings consolidatedFindings,
      List<SpecializedReviewFindings.AgentFindings> specializedFindings,
      Set<String> expectedConcernIds) {
    return SpecializedReviewConcernIds.currentRunConsolidationOrFallback(
        consolidatedFindings, specializedFindings, expectedConcernIds);
  }

  @VisibleForTesting
  SpecializedReviewFindings currentRunConflictResolutionOrFallback(
      SpecializedReviewFindings conflictResolvedFindings, SpecializedReviewFindings fallbackFindings) {
    return SpecializedReviewConcernIds.currentRunConflictResolutionOrFallback(
        conflictResolvedFindings, fallbackFindings);
  }

  @VisibleForTesting
  SpecializedReviewFindings copyRepeatedAnnotations(
      SpecializedReviewFindings targetFindings, SpecializedReviewFindings sourceFindings) {
    return SpecializedReviewRepetitionMerger.copyRepeatedAnnotations(
        targetFindings, sourceFindings);
  }

  @VisibleForTesting
  SpecializedReviewFindings applyHistoricalRepetition(
      SpecializedReviewFindings consolidatedFindings,
      SpecializedReviewFindings.HistoricalRepetitionResult historicalRepetitionResult) {
    return SpecializedReviewRepetitionMerger.applyHistoricalRepetition(
        consolidatedFindings, historicalRepetitionResult);
  }

  @VisibleForTesting
  AiResponseContent inheritRepeatedAnnotations(
      AiResponseContent response, SpecializedReviewFindings findings) {
    return SpecializedReviewRepetitionMerger.inheritRepeatedAnnotations(response, findings);
  }

  @VisibleForTesting
  List<SpecializedReviewFindings.PastComment> collectPastReviewComments(
      ChangeSetData changeSetData, GerritChange change) {
    if (isForgetThreadRequested(changeSetData)) {
      return List.of();
    }
    return pastCommentsCollector.collect(changeSetData, change);
  }

  private RawReviewRequestResult askSingleRawRequestWithFallback(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    RawReviewRequestResult rawResult = askSingleRawRequest(changeSetData, change, patchSet);
    Optional<AiModelRoute> fallbackRoute =
        rawResult == null
            ? Optional.empty()
            : config.resolveMockAiFallbackRoute(rawResult.getResponseText());
    return fallbackRoute.isEmpty()
        ? rawResult
        : askSingleRawRequest(changeSetData, change, patchSet, fallbackRoute.get());
  }

  @VisibleForTesting
  String buildSpecializedInput(
      String patchSet, SpecializedReviewTriage.AgentPlan plan) {
    List<String> sections = new ArrayList<>();
    sections.add("# Patchset\n" + patchSet);
    AiPromptSections.addSection(
        sections, "Filtered history context", plan.getHistoryContext());
    return String.join("\n\n", sections);
  }

  @VisibleForTesting
  String buildTriageInput(ChangeSetData changeSetData, GerritChange change, String patchSet) {
    List<String> sections = new ArrayList<>();
    sections.add("# Patchset\n" + patchSet);
    AiPromptSections.addSection(
        sections, "Message thread", buildMessageThreadContext(changeSetData, change));
    return String.join("\n\n", sections);
  }

  private String buildMessageThreadContext(ChangeSetData changeSetData, GerritChange change) {
    if (gerritClient == null || localizer == null || changeSetData == null || change == null) {
      return "";
    }
    if (isForgetThreadRequested(changeSetData)) {
      return "";
    }
    try {
      GerritClientData gerritClientData = gerritClient.getClientData(change);
      if (gerritClientData == null) {
        return "";
      }
      AiHistory aiHistory = new AiHistory(config, changeSetData, gerritClientData, localizer);
      return LangChainChatMessages.build(aiHistory, gerritClientData, change).stream()
          .map(LangChainChatMessages::trimmed)
          .map(this::formatThreadMessage)
          .filter(message -> !message.isBlank())
          .collect(Collectors.joining("\n\n"));
    } catch (Exception e) {
      log.debug("Unable to add Gerrit message thread to specialized triage context", e);
      return "";
    }
  }

  private String formatThreadMessage(ChatMessage message) {
    return message.type() + ":\n" + LangChainChatMessages.content(message).trim();
  }

  protected boolean isForgetThreadRequested(ChangeSetData changeSetData) {
    return changeSetData != null
        && Boolean.TRUE.equals(
            changeSetData.hasParsedCommand(ClientCommandBase.commandName(CommandSet.FORGET_THREAD)));
  }

  private boolean isSpecializedAgentStage(ReviewAssistantStage stage) {
    return stage == ReviewAssistantStage.REVIEW_COMMIT_MESSAGE
        || stage == ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT;
  }

  private boolean isCommitMessageAgent(String agent) {
    return COMMIT_MESSAGE_AGENT.equals(normalizedAgentName(agent));
  }

  private String normalizedAgentName(String agent) {
    return SpecializedReviewAgentDefinition.normalizeName(agent);
  }

  protected record CollectorResult(
      AiResponseContent response,
      SpecializedReviewFindings verificationCandidates) {}

  private record VerificationStageResult(AiResponseContent response, String requestBody) {}
}
