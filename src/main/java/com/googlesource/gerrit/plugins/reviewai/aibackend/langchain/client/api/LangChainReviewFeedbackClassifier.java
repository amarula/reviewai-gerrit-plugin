/*
 * Copyright (c) 2026. Amarula Solutions
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

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.COMMAND_PATTERN;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.isJsonObjectAsString;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.unwrapJsonCode;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account.ReviewAiUser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritCommentThreadIndex;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinition;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinitions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackClassificationInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackClassificationInput.TargetComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackClassificationResult;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackClassificationResult.Category;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiCostTracker;
import dev.langchain4j.model.chat.request.ResponseFormat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class LangChainReviewFeedbackClassifier {
  private static final String RESPONSE_SCHEMA_RESOURCE =
      "config/formatReviewFeedbackClassificationSchema.json";

  private final ResponseFormat responseFormat;
  private final LangChainExecutor executor;

  LangChainReviewFeedbackClassifier(
      Configuration config,
      AiCostTracker costTracker,
      Function<ResponseFormat, ResponseFormat> providerResponseFormat) {
    responseFormat =
        new LangChainStructuredResponseFactory(RESPONSE_SCHEMA_RESOURCE)
            .loadStructuredResponseFormat();
    executor =
        new LangChainExecutor(
            config,
            providerResponseFormat.apply(responseFormat),
            List.of(),
            false,
            null,
            costTracker);
  }

  ReviewFeedbackMemory classify(
      ChangeSetData changeSetData,
      GerritChange change,
      GerritClientData clientData,
      ReviewFeedbackMemory currentMemory,
      RequestExecutor requestExecutor)
      throws Exception {
    ReviewFeedbackClassificationInput input = buildInput(changeSetData, clientData, currentMemory);
    if (input.getComments().isEmpty()
        && (changeSetData.getConditionLabels() == null
            || changeSetData.getConditionLabels().isEmpty())) {
      return input.getCurrentMemory();
    }

    ChangeSetData classificationData = changeSetData.copy();
    classificationData.setReviewAssistantStage(ReviewAssistantStage.CLASSIFY_REVIEW_FEEDBACK);
    classificationData.setForcedStagedReview(true);
    classificationData.setReviewAssistantStageConversationSuffix(null);
    classificationData.setReviewFeedbackClassificationInput(input);
    String responseText = requestExecutor.execute(classificationData, change, "");
    if (responseText == null || !isJsonObjectAsString(responseText)) {
      throw new IllegalStateException("Review feedback classifier returned no structured response");
    }

    ReviewFeedbackClassificationResult result =
        getGson()
            .fromJson(
                unwrapJsonCode(responseText), ReviewFeedbackClassificationResult.class);
    return toMemory(input, result);
  }

  ResponseFormat getResponseFormat() {
    return responseFormat;
  }

  LangChainExecutor getExecutor() {
    return executor;
  }

  private ReviewFeedbackClassificationInput buildInput(
      ChangeSetData changeSetData,
      GerritClientData clientData,
      ReviewFeedbackMemory currentMemory) {
    ReviewFeedbackMemory memory =
        currentMemory == null ? new ReviewFeedbackMemory() : currentMemory;
    ReviewConcernLedger ledger = changeSetData.getPreviousReviewConcernLedger();
    List<ReviewConcern> concerns = concerns(ledger);
    Map<String, String> concernIdsByCommentId = new LinkedHashMap<>();
    for (ReviewConcern concern : concerns) {
      if (concern.getPreviousCommentId() != null
          && !concern.getPreviousCommentId().isBlank()) {
        concernIdsByCommentId.putIfAbsent(concern.getPreviousCommentId(), concern.getId());
      }
    }

    CommentData commentData = clientData == null ? null : clientData.getCommentData();
    Map<String, GerritComment> commentsById =
        commentData == null || commentData.getCommentMap() == null
            ? Map.of()
            : commentData.getCommentMap();
    GerritCommentThreadIndex threadIndex =
        new GerritCommentThreadIndex(commentsById.values());
    List<ReviewFeedbackClassificationInput.Comment> comments = new ArrayList<>();
    List<String> pendingCommentIds = changeSetData.getPendingReviewFeedbackCommentIds();
    if (pendingCommentIds == null) {
      pendingCommentIds = List.of();
    }
    for (String commentId : pendingCommentIds) {
      GerritComment comment = commentsById.get(commentId);
      if (comment == null) {
        throw new IllegalStateException(
            "Pending review feedback comment is missing from Gerrit: " + commentId);
      }
      if (!hasSubstantiveFeedback(comment.getMessage())) {
        continue;
      }
      List<GerritComment> lineage = threadIndex.lineage(comment);
      String threadConcernId = threadConcernId(lineage, concernIdsByCommentId);
      List<ReviewFeedbackClassificationInput.ThreadMessage> threadContext =
          lineage.subList(0, Math.max(0, lineage.size() - 1)).stream()
              .map(
                  entry ->
                      new ReviewFeedbackClassificationInput.ThreadMessage(
                          entry.getId(),
                          ReviewAiUser.matches(entry, changeSetData.getAiAccountId())
                              ? "AI"
                              : "USER",
                          entry.getMessage()))
              .toList();
      comments.add(
          new ReviewFeedbackClassificationInput.Comment(
              new TargetComment(
                  comment.getId(),
                  comment.getMessage(),
                  comment.getFilename(),
                  comment.getLine()),
              threadConcernId,
              threadContext));
    }
    return new ReviewFeedbackClassificationInput(memory, concerns, comments);
  }

  private boolean hasSubstantiveFeedback(String message) {
    if (message == null || message.isBlank()) {
      return false;
    }
    String withoutCommands = COMMAND_PATTERN.matcher(message).replaceAll("");
    String withoutLeadingMention =
        withoutCommands.replaceFirst("^\\s*@\\S+\\s*", "");
    return !withoutLeadingMention.isBlank();
  }

  private List<ReviewConcern> concerns(ReviewConcernLedger ledger) {
    if (ledger == null) {
      return List.of();
    }
    ledger.normalize();
    return ledger.getReviewers().stream()
        .flatMap(reviewer -> reviewer.getConcerns().stream())
        .toList();
  }

  private String threadConcernId(
      List<GerritComment> lineage, Map<String, String> concernIdsByCommentId) {
    for (int index = lineage.size() - 1; index >= 0; index--) {
      String concernId = concernIdsByCommentId.get(lineage.get(index).getId());
      if (concernId != null) {
        return concernId;
      }
    }
    return null;
  }

  private ReviewFeedbackMemory toMemory(
      ReviewFeedbackClassificationInput input,
      ReviewFeedbackClassificationResult result) {
    if (result == null) {
      throw new IllegalStateException("Review feedback classifier returned an empty result");
    }
    Set<String> expectedCommentIds = new HashSet<>();
    input.getComments().forEach(
        comment -> expectedCommentIds.add(comment.getTargetComment().getId()));
    Set<String> concernIds = new HashSet<>();
    input.getConcerns().forEach(concern -> concernIds.add(concern.getId()));
    Set<String> classifiedCommentIds = new HashSet<>();
    if (result.getClassifications() == null) {
      throw new IllegalStateException("Review feedback classifications are missing");
    }
    for (ReviewFeedbackClassificationResult.Classification classification :
        result.getClassifications()) {
      validateClassification(classification, concernIds, classifiedCommentIds);
    }
    if (!classifiedCommentIds.equals(expectedCommentIds)) {
      throw new IllegalStateException(
          "Review feedback classifier must classify every addressed comment exactly once");
    }
    return createMemory(result, concernIds);
  }

  private ReviewFeedbackMemory createMemory(
      ReviewFeedbackClassificationResult result,
      Set<String> concernIds) {
    Map<String, String> concernFeedback = new LinkedHashMap<>();
    if (result.getConcernFeedback() == null) {
      throw new IllegalStateException("Review concern feedback summaries are missing");
    }
    for (ReviewFeedbackClassificationResult.ConcernFeedback feedback :
        result.getConcernFeedback()) {
      if (feedback == null
          || feedback.getConcernId() == null
          || !concernIds.contains(feedback.getConcernId())
          || feedback.getSummary() == null
          || feedback.getSummary().isBlank()
          || concernFeedback.putIfAbsent(
                  feedback.getConcernId(), feedback.getSummary().trim())
              != null) {
        throw new IllegalStateException("Review feedback contains an invalid concern summary");
      }
    }
    ReviewFeedbackMemory memory = new ReviewFeedbackMemory();
    String genericFeedback = result.getGenericFeedback();
    memory.setGenericFeedback(
        genericFeedback == null || genericFeedback.isBlank()
            ? null
            : genericFeedback.trim());
    memory.setConcernFeedback(concernFeedback);
    Set<ReviewScope> disabledReviewScopes = result.getDisabledReviewScopes();
    if (disabledReviewScopes == null
        || disabledReviewScopes.contains(null)
        || disabledReviewScopes.contains(ReviewScope.FULL)) {
      throw new IllegalStateException("Review feedback contains an invalid disabled scope");
    }
    memory.setDisabledReviewScopes(Set.copyOf(disabledReviewScopes));
    Set<String> disabledSpecializedAgents = new HashSet<>();
    if (result.getDisabledSpecializedAgents() == null) {
      throw new IllegalStateException("Review feedback disabled specialized agents are missing");
    }
    for (String agent : result.getDisabledSpecializedAgents()) {
      String normalizedAgent = SpecializedReviewAgentDefinition.normalizeName(agent);
      if (SpecializedReviewAgentDefinitions.findByName(normalizedAgent).isEmpty()) {
        throw new IllegalStateException(
            "Review feedback contains an invalid disabled specialized agent");
      }
      disabledSpecializedAgents.add(normalizedAgent);
    }
    memory.setDisabledSpecializedAgents(Set.copyOf(disabledSpecializedAgents));
    return memory;
  }

  private void validateClassification(
      ReviewFeedbackClassificationResult.Classification classification,
      Set<String> concernIds,
      Set<String> classifiedCommentIds) {
    if (classification == null
        || classification.getCommentId() == null
        || classification.getCommentId().isBlank()
        || classification.getCategory() == null
        || !classifiedCommentIds.add(classification.getCommentId())) {
      throw new IllegalStateException("Review feedback contains an invalid classification");
    }
    String concernId = classification.getConcernId();
    if (classification.getCategory() == Category.CONCERN) {
      if (concernId == null || !concernIds.contains(concernId)) {
        throw new IllegalStateException(
            "Concern-related review feedback requires a known concern ID");
      }
    } else if (concernId != null && !concernId.isBlank()) {
      throw new IllegalStateException(
          "Only concern-related review feedback may reference a concern ID");
    }
  }

  @FunctionalInterface
  interface RequestExecutor {
    String execute(ChangeSetData changeSetData, GerritChange change, String patchSet)
        throws Exception;
  }
}
