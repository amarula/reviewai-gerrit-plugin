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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account.ReviewAiUser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritCommentThreadIndex;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernWorkflowInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackClassificationInput.ThreadMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class LangChainConcernWorkflowInputFactory {
  private static final Comparator<GerritComment> COMMENT_ORDER =
      Comparator.comparing(
              (GerritComment comment) ->
                  comment.getUpdated() != null ? comment.getUpdated() : comment.getDate(),
              Comparator.nullsFirst(Comparator.<String>naturalOrder()))
          .thenComparing(GerritComment::getId);

  private LangChainConcernWorkflowInputFactory() {}

  static ConcernWorkflowInput create(
      Configuration config,
      ReviewerConcerns concerns,
      String incrementalPatchSet,
      String fullPatchSet,
      ReviewFeedbackMemory reviewFeedback) {
    String fullPatchContext =
        config != null && config.getCodeContextPolicy() == CodeContextPolicies.NONE
            ? fullPatchSet
            : null;
    return new ConcernWorkflowInput(
        concerns, incrementalPatchSet, fullPatchContext, reviewFeedback);
  }

  static ConcernWorkflowInput create(
      Configuration config,
      ReviewerConcerns concerns,
      String incrementalPatchSet,
      String fullPatchSet,
      ReviewFeedbackMemory reviewFeedback,
      CommentData commentData,
      int aiAccountId) {
    ConcernWorkflowInput input =
        create(config, concerns, incrementalPatchSet, fullPatchSet, reviewFeedback);
    Map<String, List<ThreadMessage>> context = new LinkedHashMap<>();
    if (commentData != null && commentData.getCommentMap() != null) {
      Map<String, GerritComment> comments = commentData.getCommentMap();
      GerritCommentThreadIndex index = new GerritCommentThreadIndex(comments.values());
      for (ReviewConcern concern : concerns.getConcerns()) {
        String anchorId = concern.getPreviousCommentId();
        if (anchorId == null || !comments.containsKey(anchorId)) {
          continue;
        }
        comments.values().stream()
            .filter(Objects::nonNull)
            .filter(comment -> comment.getId() != null && !anchorId.equals(comment.getId()))
            .filter(
                comment ->
                    index.lineage(comment).stream()
                        .anyMatch(ancestor -> anchorId.equals(ancestor.getId())))
            .max(COMMENT_ORDER)
            .ifPresent(
                latest -> context.put(concern.getId(), latestExchange(index, latest, aiAccountId)));
      }
    }
    input.setThreadContext(context);
    return input;
  }

  private static List<ThreadMessage> latestExchange(
      GerritCommentThreadIndex index, GerritComment latest, int aiAccountId) {
    ThreadMessage message = threadMessage(latest, aiAccountId);
    if (ReviewAiUser.matches(latest, aiAccountId)) {
      return index
          .nearestAncestor(latest, comment -> !ReviewAiUser.matches(comment, aiAccountId))
          .map(user -> List.of(threadMessage(user, aiAccountId), message))
          .orElseGet(() -> List.of(message));
    }
    return List.of(message);
  }

  private static ThreadMessage threadMessage(GerritComment comment, int aiAccountId) {
    return new ThreadMessage(
        comment.getId(),
        ReviewAiUser.matches(comment, aiAccountId) ? "AI" : "USER",
        comment.getMessage());
  }
}
