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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.LangChainMemoryId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LangChainSuggestClient {
  private static final int MAX_SUGGEST_REVIEW_ITERATIONS = 3;
  private static final String SYSTEM_MESSAGE_PREFIX_KEY = "system.message.prefix";
  private static final String EMPTY_FINAL_PATCHSET_MESSAGE_KEY =
      "message.suggest.patchset.unamendable";

  private final LangChainClient client;
  private final Configuration config;
  private final PluginDataHandlerProvider pluginDataHandlerProvider;
  private final Localizer localizer;

  public LangChainSuggestClient(
      LangChainClient client,
      Configuration config,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer) {
    this.client = client;
    this.config = config;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.localizer = localizer;
  }

  public AiResponseContent ask(ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws Exception {
    List<AiResponseContent> suggestions = new ArrayList<>();
    for (ReviewAssistantStage assistantStage : getSuggestAssistantStages(changeSetData)) {
      AiResponseContent suggestion =
          askSuggestStage(changeSetData, change, patchSet, assistantStage);
      if (SuggestedPatchSetCandidate.isEmptyFinalPatchSetResponse(
          suggestion, emptyFinalPatchSetMessage())) {
        return suggestion;
      }
      if (hasContent(suggestion)) {
        suggestions.add(suggestion);
      }
    }
    return mergeSuggestionResponses(suggestions);
  }

  private List<ReviewAssistantStage> getSuggestAssistantStages(ChangeSetData changeSetData) {
    ReviewScope scope = changeSetData.getReviewScope();
    if (scope == ReviewScope.PATCHSET) {
      return List.of(ReviewAssistantStage.REVIEW_CODE);
    }
    if (scope == ReviewScope.COMMIT_MESSAGE) {
      return List.of(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    }
    return List.of(ReviewAssistantStage.REVIEW_CODE, ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
  }

  private AiResponseContent askSuggestStage(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      ReviewAssistantStage assistantStage)
      throws Exception {
    ChangeSetData reviewData = buildStageData(changeSetData, assistantStage, false);
    if (!hasExistingReviewConversation(reviewData, change)) {
      LangChainClient.ReviewRequestResult reviewResult =
          client.askSingleRequest(reviewData, change, patchSet);
      client.setRequestBody(reviewResult == null ? null : reviewResult.getRequestBody());
    }

    AiResponseContent lastSuggestion = null;
    AiResponseContent candidateReview = null;
    String lastCandidatePatchSet = null;
    String reviewedPatchSet = patchSet;
    for (int i = 0; i < MAX_SUGGEST_REVIEW_ITERATIONS; i++) {
      ChangeSetData suggestionData = buildStageData(changeSetData, assistantStage, true);
      LangChainClient.ReviewRequestResult suggestionResult =
          client.askSingleRequest(
              suggestionData,
              change,
              buildSuggestionRequestPatchSet(reviewedPatchSet, candidateReview));
      client.setRequestBody(suggestionResult == null ? null : suggestionResult.getRequestBody());
      lastSuggestion = suggestionResult == null ? null : suggestionResult.getResponseContent();
      clearSuggestionScores(lastSuggestion);
      if (!hasContent(lastSuggestion)) {
        return lastSuggestion;
      }

      String candidatePatchSet =
          buildCandidatePatchSet(reviewedPatchSet, lastSuggestion, assistantStage);
      lastCandidatePatchSet = candidatePatchSet;
      if (assistantStage == ReviewAssistantStage.REVIEW_CODE
          && !SuggestedPatchSetCandidate.hasDisplayablePatchSet(candidatePatchSet)) {
        SuggestedPatchSetCandidate.appendFinalPatchSet(
            lastSuggestion, candidatePatchSet, assistantStage, emptyFinalPatchSetMessage());
        return lastSuggestion;
      }

      ChangeSetData candidateReviewData = buildStageData(changeSetData, assistantStage, false);
      LangChainClient.ReviewRequestResult candidateReviewResult =
          client.askSingleRequest(candidateReviewData, change, candidatePatchSet);
      client.setRequestBody(
          candidateReviewResult == null ? null : candidateReviewResult.getRequestBody());
      candidateReview =
          candidateReviewResult == null ? null : candidateReviewResult.getResponseContent();
      if (isPositiveReview(candidateReview)) {
        SuggestedPatchSetCandidate.appendFinalPatchSet(
            lastSuggestion, candidatePatchSet, assistantStage, emptyFinalPatchSetMessage());
        return lastSuggestion;
      }
      reviewedPatchSet = candidatePatchSet;
    }
    if (lastSuggestion == null) {
      return emptyResponse();
    }
    SuggestedPatchSetCandidate.appendFinalPatchSet(
        lastSuggestion, lastCandidatePatchSet, assistantStage, emptyFinalPatchSetMessage());
    return lastSuggestion;
  }

  private ChangeSetData buildStageData(
      ChangeSetData changeSetData, ReviewAssistantStage assistantStage, boolean suggestMode) {
    ChangeSetData stageData = changeSetData.copy();
    stageData.setForcedReview(true);
    stageData.setForcedStagedReview(true);
    stageData.setReviewAssistantStage(assistantStage);
    stageData.setReviewScope(toReviewScope(assistantStage));
    stageData.setSuggestMode(suggestMode);
    return stageData;
  }

  private String buildCandidatePatchSet(
      String patchSet, AiResponseContent suggestion, ReviewAssistantStage assistantStage) {
    if (assistantStage == ReviewAssistantStage.REVIEW_CODE) {
      String patchSetFix = SuggestedPatchSetCandidate.suggestionText(suggestion);
      if (patchSetFix.isBlank()) {
        return patchSet;
      }
      return PatchSetMerger.merge(patchSet, patchSetFix);
    }
    return SuggestedPatchSetCandidate.merge(patchSet, suggestion, assistantStage);
  }

  private ReviewScope toReviewScope(ReviewAssistantStage assistantStage) {
    return assistantStage == ReviewAssistantStage.REVIEW_COMMIT_MESSAGE
        ? ReviewScope.COMMIT_MESSAGE
        : ReviewScope.PATCHSET;
  }

  private boolean hasExistingReviewConversation(ChangeSetData changeSetData, GerritChange change) {
    AiProviderType providerType = config.getAiProviderType();
    if (providerType == AiProviderType.OPENAI) {
      if (pluginDataHandlerProvider == null) {
        return false;
      }
      return new OpenAiConversation(
              config, pluginDataHandlerProvider, getConversationKey(changeSetData))
          .hasExistingConversation();
    }
    return !client.buildMemory(LangChainMemoryId.from(changeSetData, change)).messages().isEmpty();
  }

  private String getConversationKey(ChangeSetData changeSetData) {
    String conversationKey = OpenAiConversation.KEY_CONVERSATION_ID;
    if (changeSetData.getReviewAssistantStage() == ReviewAssistantStage.REVIEW_CODE
        || changeSetData.getReviewAssistantStage() == ReviewAssistantStage.REVIEW_COMMIT_MESSAGE) {
      conversationKey =
          OpenAiConversation.getMultiAgentConversationKey(
              changeSetData.getReviewAssistantStage());
    }
    return conversationKey;
  }

  private String buildSuggestionRequestPatchSet(
      String patchSet, AiResponseContent previousCandidateReview) {
    if (!hasContent(previousCandidateReview)) {
      return patchSet;
    }
    return patchSet
        + "\n\nPrevious review of the merged candidate patchset to address:\n"
        + responseText(previousCandidateReview);
  }

  private AiResponseContent mergeSuggestionResponses(List<AiResponseContent> suggestions) {
    AiResponseContent mergedResponse = emptyResponse();
    List<AiReplyItem> replies = new ArrayList<>();
    for (AiResponseContent suggestion : suggestions) {
      if (suggestion.getReplies() != null) {
        replies.addAll(suggestion.getReplies());
      }
    }
    mergedResponse.setReplies(replies);
    return mergedResponse;
  }

  private AiResponseContent emptyResponse() {
    AiResponseContent response = new AiResponseContent("");
    response.setReplies(new ArrayList<>());
    return response;
  }

  private boolean hasContent(AiResponseContent responseContent) {
    return responseContent != null
        && responseContent.getReplies() != null
        && responseContent.getReplies().stream()
            .map(AiReplyItem::getReply)
            .anyMatch(reply -> reply != null && !reply.isBlank());
  }

  private boolean isPositiveReview(AiResponseContent review) {
    if (review == null || review.getReplies() == null || review.getReplies().isEmpty()) {
      return true;
    }
    for (AiReplyItem reply : review.getReplies()) {
      if (reply.isRepeated() || reply.isConflicting()) {
        continue;
      }
      Double score = reply.getScore();
      if (score == null || score < 0) {
        return false;
      }
    }
    return true;
  }

  private void clearSuggestionScores(AiResponseContent suggestion) {
    if (suggestion == null || suggestion.getReplies() == null) {
      return;
    }
    suggestion.getReplies().forEach(reply -> reply.setScore(null));
  }

  private String responseText(AiResponseContent responseContent) {
    if (responseContent == null || responseContent.getReplies() == null) {
      return "";
    }
    return String.join(
        "\n\n",
        responseContent.getReplies().stream()
            .map(AiReplyItem::getReply)
            .filter(reply -> reply != null && !reply.isBlank())
            .toList());
  }

  private String emptyFinalPatchSetMessage() {
    return getPrefixedSystemMessage(localizer.getText(EMPTY_FINAL_PATCHSET_MESSAGE_KEY));
  }

  private String getPrefixedSystemMessage(String message) {
    String prefix =
        Optional.ofNullable(localizer.getText(SYSTEM_MESSAGE_PREFIX_KEY)).orElse("").trim();
    if (prefix.isEmpty() || message.stripLeading().startsWith(prefix)) {
      return message;
    }
    return prefix + ' ' + message;
  }
}
