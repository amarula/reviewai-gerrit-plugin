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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.MessageSanitizer.sanitizeAiMessage;

import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.CommentInfo;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class PublishedCommentConcernBinder {
  static final String CONCERN_REVIEW_TAG_PREFIX = "reviewai:concerns:";

  void tagReview(ReviewInput reviewInput, List<ReviewBatch> reviewBatches) {
    if (reviewInput.comments != null && hasConcernBatches(reviewBatches)) {
      reviewInput.tag = CONCERN_REVIEW_TAG_PREFIX + UUID.randomUUID();
    }
  }

  Optional<Set<String>> snapshotCommentIds(ChangeApi changeApi, String reviewTag) {
    if (reviewTag == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(commentIds(changeApi.commentsRequest().get()));
    } catch (Exception e) {
      log.warn("Could not snapshot comments before publishing concern comments", e);
      return Optional.empty();
    }
  }

  Map<String, String> bind(
      ChangeApi changeApi,
      List<ReviewBatch> reviewBatches,
      String reviewTag,
      Optional<Set<String>> existingCommentIds) {
    if (reviewTag == null) {
      return Map.of();
    }
    try {
      List<PublishedComment> allComments = publishedComments(changeApi);
      List<PublishedComment> publishedComments =
          allComments.stream()
              .filter(comment -> reviewTag.equals(comment.comment().tag))
              .collect(Collectors.toCollection(ArrayList::new));
      if (publishedComments.isEmpty() && existingCommentIds.isPresent()) {
        publishedComments =
            allComments.stream()
                .filter(comment -> !existingCommentIds.get().contains(comment.comment().id))
                .collect(Collectors.toCollection(ArrayList::new));
        log.warn(
            "Published concern comments do not expose review tag {}; binding {} newly created comments",
            reviewTag,
            publishedComments.size());
      }
      Map<String, String> commentIdsByConcern = new LinkedHashMap<>();
      for (ReviewBatch batch : reviewBatches) {
        if (batch.getConcernId() == null || batch.getConcernId().isBlank()) {
          continue;
        }
        Optional<PublishedComment> publishedComment =
            publishedComments.stream()
                .filter(comment -> matches(batch, comment))
                .findFirst();
        if (publishedComment.isEmpty()) {
          log.warn("Could not bind published comment for concern {}", batch.getConcernId());
          continue;
        }
        CommentInfo comment = publishedComment.get().comment();
        if (comment.id != null && !comment.id.isBlank()) {
          commentIdsByConcern.putIfAbsent(batch.getConcernId(), comment.id);
        }
        publishedComments.remove(publishedComment.get());
      }
      return commentIdsByConcern;
    } catch (Exception e) {
      log.warn("Could not retrieve published concern comments", e);
      return Map.of();
    }
  }

  private List<PublishedComment> publishedComments(ChangeApi changeApi) throws Exception {
    return changeApi.commentsRequest().get().entrySet().stream()
        .flatMap(
            entry ->
                entry.getValue().stream().map(comment -> new PublishedComment(entry.getKey(), comment)))
        .collect(Collectors.toCollection(ArrayList::new));
  }

  private Set<String> commentIds(Map<String, List<CommentInfo>> comments) {
    Set<String> ids = new HashSet<>();
    comments.values().stream()
        .flatMap(List::stream)
        .map(comment -> comment.id)
        .filter(Objects::nonNull)
        .forEach(ids::add);
    return ids;
  }

  private boolean hasConcernBatches(List<ReviewBatch> reviewBatches) {
    return reviewBatches.stream()
        .anyMatch(batch -> batch.getConcernId() != null && !batch.getConcernId().isBlank());
  }

  static boolean isTaggedConcernComment(CommentInfo comment) {
    return comment != null
        && comment.tag != null
        && comment.tag.startsWith(CONCERN_REVIEW_TAG_PREFIX);
  }

  private boolean matches(ReviewBatch batch, PublishedComment publishedComment) {
    CommentInfo comment = publishedComment.comment();
    return Objects.equals(batch.getFilename(), publishedComment.filename())
        && Objects.equals(sanitizeAiMessage(batch.getContent()), comment.message)
        && Objects.equals(batch.getId(), comment.inReplyTo)
        && matchesLocation(batch, comment);
  }

  private boolean matchesLocation(ReviewBatch batch, CommentInfo comment) {
    if (batch.getRange() == null) {
      return Objects.equals(batch.getLine(), comment.line);
    }
    return comment.range != null
        && batch.getRange().getStartLine() == comment.range.startLine
        && batch.getRange().getStartCharacter() == comment.range.startCharacter
        && batch.getRange().getEndLine() == comment.range.endLine
        && batch.getRange().getEndCharacter() == comment.range.endCharacter;
  }

  private record PublishedComment(String filename, CommentInfo comment) {}
}
