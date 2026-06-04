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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

@Slf4j
public class LangChainSuggestClient {
  private static final String COMMIT_MESSAGE_FILENAME = "/COMMIT_MSG";
  private static final String SUGGESTION_FENCE = "```suggestion";

  private final LangChainClient client;

  public LangChainSuggestClient(LangChainClient client) {
    this.client = client;
  }

  public AiResponseContent ask(ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws Exception {
    List<AiReplyItem> negativeReplies = askReview(changeSetData, change, patchSet);
    List<AiReplyItem> suggestions =
        negativeReplies.isEmpty()
            ? List.of()
            : askSuggestionRequests(changeSetData, change, patchSet, negativeReplies);
    AiResponseContent response = new AiResponseContent("");
    response.setReplies(suggestions);
    return response;
  }

  private List<AiReplyItem> askReview(
      ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws Exception {
    ChangeSetData reviewData = buildReviewData(changeSetData);
    AiResponseContent reviewResponse = client.askReview(reviewData, change, patchSet);
    if (reviewResponse == null) {
      return List.of();
    }

    List<AiReplyItem> negativeReplies = negativeReplies(reviewResponse);
    for (AiReplyItem reviewReply : negativeReplies) {
      prepareReviewLocation(reviewReply, reviewData.getReviewScope(), patchSet);
    }
    return negativeReplies;
  }

  private List<AiReplyItem> askSuggestionRequests(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      List<AiReplyItem> negativeReplies)
      throws Exception {
    assignReplyIds(negativeReplies);
    if (shouldSplitSuggestionRequests(changeSetData)) {
      List<AiReplyItem> suggestions = new ArrayList<>();
      suggestions.addAll(
          askSuggestions(
              changeSetData,
              change,
              patchSet,
              negativeReplies.stream()
                  .filter(reply -> !COMMIT_MESSAGE_FILENAME.equals(reply.getFilename()))
                  .toList(),
              ReviewScope.PATCHSET));
      suggestions.addAll(
          askSuggestions(
              changeSetData,
              change,
              patchSet,
              negativeReplies.stream()
                  .filter(reply -> COMMIT_MESSAGE_FILENAME.equals(reply.getFilename()))
                  .toList(),
              ReviewScope.COMMIT_MESSAGE));
      return suggestions;
    }
    return askSuggestions(
        changeSetData, change, patchSet, negativeReplies, changeSetData.getReviewScope());
  }

  private boolean shouldSplitSuggestionRequests(ChangeSetData changeSetData) {
    return changeSetData.getReviewScope() == null
        && client instanceof LangChainMultiAgentReviewClient;
  }

  private List<AiReplyItem> askSuggestions(
      ChangeSetData changeSetData,
      GerritChange change,
      String patchSet,
      List<AiReplyItem> negativeReplies,
      ReviewScope reviewScope)
      throws Exception {
    if (negativeReplies.isEmpty()) {
      return List.of();
    }
    log.info(
        "Requesting Gerrit suggested edits in one AI query for {} negative review replies",
        negativeReplies.size());
    ChangeSetData suggestionData = buildSuggestionData(changeSetData, reviewScope);
    LangChainClient.ReviewRequestResult suggestionResult =
        client.askSingleRequest(
            suggestionData, change, buildSuggestionRequest(patchSet, negativeReplies));
    client.setRequestBody(suggestionResult == null ? null : suggestionResult.getRequestBody());
    if (suggestionResult == null || suggestionResult.getResponseContent() == null) {
      return List.of();
    }

    Map<Integer, AiReplyItem> reviewsById =
        negativeReplies.stream().collect(Collectors.toMap(AiReplyItem::getId, Function.identity()));
    Set<Integer> commitMessageReviewIds =
        negativeReplies.stream()
            .filter(reply -> COMMIT_MESSAGE_FILENAME.equals(reply.getFilename()))
            .map(AiReplyItem::getId)
            .collect(Collectors.toSet());
    List<AiReplyItem> suggestions = new ArrayList<>();
    Set<Integer> suggestedReviewIds = new HashSet<>();
    boolean commitMessageSuggestionAdded = false;
    for (AiReplyItem suggestion : responseReplies(suggestionResult.getResponseContent())) {
      Integer reviewId = suggestion.getId();
      AiReplyItem reviewReply = reviewsById.get(reviewId);
      if (reviewReply == null) {
        log.warn("Ignoring AI suggestion without a matching negative review ID: {}", suggestion);
        continue;
      }
      boolean commitMessageSuggestion =
          COMMIT_MESSAGE_FILENAME.equals(reviewReply.getFilename());
      if (commitMessageSuggestion && commitMessageSuggestionAdded) {
        log.warn("Ignoring additional AI commit-message suggestion for negative review ID {}", reviewId);
        continue;
      }
      suggestion.setScore(null);
      if (prepareNativeSuggestedEdit(suggestion, reviewReply)) {
        if (commitMessageSuggestion) {
          suggestedReviewIds.addAll(commitMessageReviewIds);
          commitMessageSuggestionAdded = true;
        } else {
          suggestedReviewIds.add(reviewId);
        }
        // IDs only correlate suggestions with negative reviews. Gerrit must resolve the edit range
        // from the suggestion target instead of treating the ID as an existing comment ID.
        suggestion.setId(null);
        suggestions.add(suggestion);
      } else {
        log.warn(
            "Ignoring AI response that cannot be converted to a Gerrit suggested edit for negative"
                + " review ID {}: {}",
            reviewId,
            suggestion);
      }
    }
    Set<Integer> missingReviewIds = new HashSet<>(reviewsById.keySet());
    missingReviewIds.removeAll(suggestedReviewIds);
    if (!missingReviewIds.isEmpty()) {
      log.warn("AI did not provide a valid suggested edit for negative review IDs {}", missingReviewIds);
    }
    return suggestions;
  }

  private ChangeSetData buildReviewData(ChangeSetData changeSetData) {
    ChangeSetData reviewData = changeSetData.copy();
    reviewData.setForcedReview(true);
    reviewData.setSuggestMode(false);
    ReviewScope scope = changeSetData.getReviewScope();
    if (scope == ReviewScope.PATCHSET || scope == ReviewScope.COMMIT_MESSAGE) {
      reviewData.setForcedStagedReview(true);
      reviewData.setReviewAssistantStage(toReviewAssistantStage(scope));
    } else {
      reviewData.setForcedStagedReview(false);
    }
    return reviewData;
  }

  private ChangeSetData buildSuggestionData(ChangeSetData changeSetData, ReviewScope reviewScope) {
    ChangeSetData suggestionData = changeSetData.copy();
    suggestionData.setForcedReview(true);
    suggestionData.setForcedStagedReview(true);
    suggestionData.setSuggestMode(true);
    if (reviewScope == ReviewScope.PATCHSET || reviewScope == ReviewScope.COMMIT_MESSAGE) {
      suggestionData.setReviewScope(reviewScope);
      suggestionData.setReviewAssistantStage(toReviewAssistantStage(reviewScope));
    }
    return suggestionData;
  }

  private ReviewAssistantStage toReviewAssistantStage(ReviewScope scope) {
    return scope == ReviewScope.COMMIT_MESSAGE
        ? ReviewAssistantStage.REVIEW_COMMIT_MESSAGE
        : ReviewAssistantStage.REVIEW_CODE;
  }

  private List<AiReplyItem> negativeReplies(AiResponseContent responseContent) {
    return responseReplies(responseContent).stream()
        .filter(reply -> reply.getScore() != null && reply.getScore() < 0)
        .map(this::copyReply)
        .toList();
  }

  private AiReplyItem copyReply(AiReplyItem reply) {
    return AiReplyItem.builder()
        .id(reply.getId())
        .reply(reply.getReply())
        .score(reply.getScore())
        .relevance(reply.getRelevance())
        .repeated(reply.isRepeated())
        .conflicting(reply.isConflicting())
        .filename(reply.getFilename())
        .lineNumber(reply.getLineNumber())
        .codeSnippet(reply.getCodeSnippet())
        .build();
  }

  private List<AiReplyItem> responseReplies(AiResponseContent responseContent) {
    if (responseContent.getReplies() == null) {
      return List.of();
    }
    return responseContent.getReplies().stream()
        .filter(reply -> reply.getReply() != null && !reply.getReply().isBlank())
        .toList();
  }

  private void assignReplyIds(List<AiReplyItem> negativeReplies) {
    for (int i = 0; i < negativeReplies.size(); i++) {
      negativeReplies.get(i).setId(i);
    }
  }

  private String buildSuggestionRequest(String patchSet, List<AiReplyItem> negativeReplies) {
    return patchSet
        + "\n\nGenerate one or more Gerrit suggested edits for every negative review reply:\n"
        + getGson().toJson(negativeReplies);
  }

  private void prepareReviewLocation(
      AiReplyItem reviewReply, ReviewScope reviewScope, String patchSet) {
    boolean commitMessageReview =
        reviewScope == ReviewScope.COMMIT_MESSAGE
            || reviewScope != ReviewScope.PATCHSET && reviewReply.getFilename() == null;
    if (!commitMessageReview) {
      return;
    }
    reviewReply.setFilename(COMMIT_MESSAGE_FILENAME);
    reviewReply.setLineNumber(null);
    reviewReply.setCodeSnippet(extractCommitMessage(patchSet));
  }

  private String extractCommitMessage(String patchSet) {
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

  private boolean prepareNativeSuggestedEdit(AiReplyItem suggestion, AiReplyItem reviewReply) {
    if (!hasSuggestionFence(suggestion)) {
      return false;
    }
    if (COMMIT_MESSAGE_FILENAME.equals(reviewReply.getFilename())) {
      if (!COMMIT_MESSAGE_FILENAME.equals(suggestion.getFilename())) {
        return false;
      }
      suggestion.setLineNumber(null);
      suggestion.setCodeSnippet(reviewReply.getCodeSnippet());
      return suggestion.getCodeSnippet() != null;
    }
    return hasCodeSuggestionTarget(suggestion);
  }

  private boolean hasSuggestionFence(AiReplyItem suggestion) {
    return suggestion.getReply().contains(SUGGESTION_FENCE);
  }

  private boolean hasCodeSuggestionTarget(AiReplyItem suggestion) {
    return suggestion.getFilename() != null
        && !suggestion.getFilename().isBlank()
        && !COMMIT_MESSAGE_FILENAME.equals(suggestion.getFilename())
        && suggestion.getLineNumber() != null
        && suggestion.getCodeSnippet() != null
        && !suggestion.getCodeSnippet().isBlank();
  }
}
