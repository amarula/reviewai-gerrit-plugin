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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinition;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinitions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptSections;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainSuggestClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level1.LangChainMultiAgentReviewClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.web.ReviewAgentConversationStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.unwrapJsonCode;

@Slf4j
@Singleton
public class LangChainSpecializedAgentReviewClient extends LangChainMultiAgentReviewClient {
  private static final String COMMIT_MESSAGE_AGENT = "COMMIT_MESSAGE";

  private final Executor executor;
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
  public LangChainSpecializedAgentReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      Executor executor) {
    this(config, codeContextPolicy, gerritClient, localizer, null, null, null, executor);
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
      Executor executor) {
    super(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        pluginDataHandlerProvider,
        conversationStore,
        chatMemoryStore,
        executor);
    this.executor = executor;
    this.codeContextPolicy = codeContextPolicy;
    this.gerritClient = gerritClient;
    this.localizer = localizer;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.chatMemoryStore = chatMemoryStore;
  }

  @Override
  protected LangChainSuggestClient getSuggestClient() {
    return new LangChainSuggestClient(
        new LangChainClient(
            config,
            codeContextPolicy,
            gerritClient,
            localizer,
            pluginDataHandlerProvider,
            chatMemoryStore));
  }

  @Override
  protected AiResponseContent askReview(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    if (change.getIsCommentEvent() && !changeSetData.getForcedReview()) {
      return super.askReview(changeSetData, change, patchSet);
    }
    if (changeSetData.getForcedStagedReview()) {
      return super.askReview(changeSetData, change, patchSet);
    }

    SpecializedReviewTriage triage = askTriage(changeSetData, change, patchSet);
    List<SpecializedReviewTriage.AgentPlan> enabledPlans = enabledPlans(changeSetData, triage);
    if (enabledPlans.isEmpty()) {
      AiResponseContent response = new AiResponseContent("");
      response.setReplies(List.of());
      return response;
    }

    List<SpecializedReviewAgentReplies> specializedReplies =
        askSpecializedAgents(changeSetData, change, patchSet, enabledPlans);
    AiResponseContent collectorResponse =
        askCollector(changeSetData, change, specializedReplies);
    return collectorResponse == null ? new AiResponseContent("") : collectorResponse;
  }

  @Override
  protected boolean shouldIncludeInitialHistory(ChangeSetData changeSetData) {
    return !isSpecializedAgentStage(changeSetData.getReviewAssistantStage());
  }

  protected SpecializedReviewTriage askTriage(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    ChangeSetData triageData = changeSetData.copy();
    triageData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_TRIAGE);
    triageData.setForcedStagedReview(true);
    RawReviewRequestResult result = askSingleRawRequest(triageData, change, patchSet);
    setRequestBody(result == null ? null : result.getRequestBody());
    return result == null
        ? new SpecializedReviewTriage()
        : parseTriageResponse(result.getResponseText());
  }

  @VisibleForTesting
  protected SpecializedReviewTriage parseTriageResponse(String responseText) {
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

  protected AiResponseContent askSpecializedAgent(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      SpecializedReviewTriage.AgentPlan plan)
      throws Exception {
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
    ReviewRequestResult result =
        askSingleRequest(agentData, change, buildSpecializedInput(patchSet, plan));
    return result == null ? null : result.getResponseContent();
  }

  protected AiResponseContent askCollector(
      ChangeSetData changeSetData,
      GerritChange change,
      List<SpecializedReviewAgentReplies> specializedReplies)
      throws Exception {
    ChangeSetData collectorData = changeSetData.copy();
    collectorData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_COLLECTOR);
    collectorData.setForcedStagedReview(true);
    ReviewRequestResult result =
        askSingleRequest(collectorData, change, getGson().toJson(specializedReplies));
    setRequestBody(result == null ? null : result.getRequestBody());
    return result == null ? null : result.getResponseContent();
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

  private List<SpecializedReviewAgentReplies> askSpecializedAgents(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      List<SpecializedReviewTriage.AgentPlan> enabledPlans)
      throws Exception {
    List<CompletableFuture<SpecializedReviewAgentReplies>> futures = new ArrayList<>();
    for (SpecializedReviewTriage.AgentPlan plan : enabledPlans) {
      futures.add(
          CompletableFuture.supplyAsync(
              () -> {
                try {
                  AiResponseContent response =
                      askSpecializedAgent(changeSetData, change, patchSet, plan);
                  return new SpecializedReviewAgentReplies(
                      normalizedAgentName(plan.getAgent()),
                      response == null || response.getReplies() == null
                          ? List.of()
                          : response.getReplies());
                } catch (Exception e) {
                  throw new CompletionException(e);
                }
              },
              executor));
    }

    List<SpecializedReviewAgentReplies> replies = new ArrayList<>();
    try {
      for (CompletableFuture<SpecializedReviewAgentReplies> future : futures) {
        replies.add(future.join());
      }
    } catch (CompletionException e) {
      if (e.getCause() instanceof Exception exception) {
        throw exception;
      }
      throw e;
    }
    return replies;
  }

  @VisibleForTesting
  protected String buildSpecializedInput(
      String patchSet, SpecializedReviewTriage.AgentPlan plan) {
    List<String> sections = new ArrayList<>();
    sections.add("# Commit message\n" + extractCommitMessage(patchSet));
    AiPromptSections.addSection(
        sections, "Selected patchset context", plan.getPatchsetContext());
    AiPromptSections.addSection(
        sections, "Filtered history context", plan.getHistoryContext());
    return String.join("\n\n", sections);
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

  private String extractCommitMessage(String patchSet) {
    if (patchSet == null) {
      return "";
    }
    int separatorIndex = patchSet.indexOf("\n---\n");
    String header = separatorIndex >= 0 ? patchSet.substring(0, separatorIndex) : patchSet;
    int subjectIndex = header.indexOf("Subject: ");
    if (subjectIndex >= 0) {
      header = header.substring(subjectIndex + "Subject: ".length());
    }
    int changeIdIndex = header.indexOf("\nChange-Id:");
    if (changeIdIndex >= 0) {
      header = header.substring(0, changeIdIndex);
    }
    return header.strip();
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
