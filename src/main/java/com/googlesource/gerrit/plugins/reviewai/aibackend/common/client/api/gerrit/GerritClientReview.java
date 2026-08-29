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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.client.Comment;
import com.google.gerrit.entities.LabelId;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.GerritReviewException;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.MessageSanitizer.sanitizeAiMessage;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

@Slf4j
public class GerritClientReview extends GerritClientAccount {
  private final Localizer localizer;
  private final PublishedCommentConcernBinder concernBinder;

  private GerritChange change;

  @VisibleForTesting
  @Inject
  public GerritClientReview(
      Configuration config,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer) {
    super(config);
    this.localizer = localizer;
    concernBinder = new PublishedCommentConcernBinder();
    log.debug("GerritClientReview initialized.");
  }

  public void setReview(
      GerritChange change,
      List<ReviewBatch> reviewBatches,
      ChangeSetData changeSetData,
      Integer reviewScore)
      throws Exception {
    setReviewAndGetPublishedCommentIds(change, reviewBatches, changeSetData, reviewScore, null);
  }

  /**
   * Collects replies that resolve open root threads published by ReviewAI and no longer actionable.
   *
   * <p>Gerrit resolves a thread by publishing a reply whose {@code unresolved} field is false.
   * Unbound comments and comments outside ReviewAI's tagged concern threads are intentionally left
   * untouched.
   */
  private Map<String, List<CommentInput>> getInactiveConcernResolutionComments(
      GerritChange change, ChangeApi changeApi, AiResponseContent response) {
    if (response == null || response.getPendingConcernUpdates() == null) {
      return Map.of();
    }
    List<ReviewConcern> inactiveConcerns =
        response.getPendingConcernUpdates().get(change.getFullChangeId()).stream()
            .flatMap(ledger -> ledger.getReviewers().stream())
            .flatMap(reviewer -> reviewer.getConcerns().stream())
            .filter(concern -> concern.getStatus().shouldResolveGerritThread())
            .filter(concern -> concern.getPreviousCommentId() != null)
            .filter(concern -> !concern.getPreviousCommentId().isBlank())
            .toList();
    if (inactiveConcerns.isEmpty()) {
      return Map.of();
    }

    try {
      Map<String, List<CommentInfo>> comments = changeApi.commentsRequest().get();
      Map<String, CommentInfo> commentsById = new LinkedHashMap<>();
      Map<String, String> filenamesByCommentId = new HashMap<>();
      Map<String, List<CommentInfo>> commentsByParentId = new HashMap<>();
      comments.forEach(
          (filename, fileComments) ->
              fileComments.forEach(
                  comment -> {
                    if (comment.id != null) {
                      commentsById.put(comment.id, comment);
                      filenamesByCommentId.put(comment.id, filename);
                    }
                    if (comment.inReplyTo != null) {
                      commentsByParentId
                          .computeIfAbsent(comment.inReplyTo, unused -> new ArrayList<>())
                          .add(comment);
                    }
                  }));

      Map<String, List<CommentInput>> resolutionComments = new LinkedHashMap<>();
      Set<String> resolvedCommentIds = new HashSet<>();
      for (ReviewConcern concern : inactiveConcerns) {
        CommentInfo comment = commentsById.get(concern.getPreviousCommentId());
        if (!isOpenReviewAiRootThread(comment, commentsByParentId)
            || !resolvedCommentIds.add(comment.id)) {
          continue;
        }
        CommentInput resolution = new CommentInput();
        resolution.message = resolutionMessage(concern);
        resolution.inReplyTo = comment.id;
        resolution.line = comment.line;
        resolution.unresolved = false;
        resolutionComments
            .computeIfAbsent(filenamesByCommentId.get(comment.id), unused -> new ArrayList<>())
            .add(resolution);
      }
      return resolutionComments;
    } catch (Exception e) {
      log.warn("Could not collect inactive ReviewAI concern thread resolutions", e);
      return Map.of();
    }
  }

  public Map<String, String> setReviewAndGetPublishedCommentIds(
      GerritChange change,
      List<ReviewBatch> reviewBatches,
      ChangeSetData changeSetData,
      Integer reviewScore)
      throws Exception {
    return setReviewAndGetPublishedCommentIds(
        change, reviewBatches, changeSetData, reviewScore, null);
  }

  public Map<String, String> setReviewAndGetPublishedCommentIds(
      GerritChange change,
      List<ReviewBatch> reviewBatches,
      ChangeSetData changeSetData,
      Integer reviewScore,
      AiResponseContent response)
      throws Exception {
    log.debug("Setting review for change ID: {}", change.getFullChangeId());
    this.change = change;
    ReviewInput reviewInput = buildReview(reviewBatches, changeSetData, reviewScore);
    try (ManualRequestContext ignored = config.openRequestContext()) {
      ChangeApi changeApi = change.getChangeApi(config);
      appendConcernResolutionComments(
          reviewInput, getInactiveConcernResolutionComments(change, changeApi, response));
      if (reviewInput.comments == null && reviewInput.message == null && reviewInput.labels == null) {
        log.debug("No comments, messages, or labels to post for review.");
        return Map.of();
      }
      concernBinder.tagReview(reviewInput, reviewBatches);
      Optional<Set<String>> existingCommentIds =
          concernBinder.snapshotCommentIds(changeApi, reviewInput.tag);
      ReviewResult result =
          changeApi.current().review(reviewInput);

      if (!Strings.isNullOrEmpty(result.error)) {
        log.error("Review setting failed with status code: {}", result.error);
        throw new GerritReviewException(result.error);
      }
      ChangeApi refreshedChangeApi = change.getChangeApi(config);
      return concernBinder.bind(
          refreshedChangeApi, reviewBatches, reviewInput.tag, existingCommentIds);
    }
  }

  private static void appendConcernResolutionComments(
      ReviewInput reviewInput, Map<String, List<CommentInput>> resolutionComments) {
    if (resolutionComments.isEmpty()) {
      return;
    }
    if (reviewInput.comments == null) {
      reviewInput.comments = new LinkedHashMap<>();
    }
    resolutionComments.forEach(
        (filename, comments) ->
            reviewInput.comments
                .computeIfAbsent(filename, unused -> new ArrayList<>())
                .addAll(comments));
  }

  public void setReview(
      GerritChange change, List<ReviewBatch> reviewBatches, ChangeSetData changeSetData)
      throws Exception {
    setReview(change, reviewBatches, changeSetData, null);
  }

  private ReviewInput buildReview(
      List<ReviewBatch> reviewBatches, ChangeSetData changeSetData, Integer reviewScore) {
    log.debug("Building review input.");
    ReviewInput reviewInput = ReviewInput.create();
    Map<String, List<CommentInput>> comments = new HashMap<>();
    String systemMessage = localizer.getText("message.empty.review");
    if (changeSetData.getReviewSystemMessage() != null) {
      systemMessage = changeSetData.getReviewSystemMessage();
      reviewInput.notify = NotifyHandling.NONE;
    } else if (!changeSetData.shouldHideAiReview()) {
      comments = getReviewComments(reviewBatches);
      if (reviewScore != null) {
        reviewInput.label(LabelId.CODE_REVIEW, reviewScore);
      }
    }
    if (!shouldSuppressSystemMessage(changeSetData, reviewScore)) {
      updateSystemMessage(changeSetData, reviewInput, comments.isEmpty(), systemMessage);
    }

    if (!comments.isEmpty()) {
      reviewInput.comments = comments;
    }
    return reviewInput;
  }

  private void updateSystemMessage(
      ChangeSetData changeSetData,
      ReviewInput reviewInput,
      boolean emptyComments,
      String systemMessage) {
    List<String> messages = new ArrayList<>();
    if (changeSetData.getReviewNoticeMessage() != null) {
      messages.add(
          SystemMessageFormatter.getPrefixedSystemMessage(
              localizer, changeSetData.getReviewNoticeMessage()));
    }
    if (changeSetData.getReviewRepeatedCommentsMessage() != null) {
      messages.add(
          SystemMessageFormatter.getPrefixedSystemMessage(
              localizer, changeSetData.getReviewRepeatedCommentsMessage()));
    }
    if (emptyComments
        && changeSetData.getReviewRepeatedCommentsMessage() == null
        && !shouldSuppressEmptyReviewMessage(changeSetData)) {
      messages.add(SystemMessageFormatter.getPrefixedSystemMessage(localizer, systemMessage));
    }
    SystemMessageFormatter.appendConfigurationWarningMessages(config, localizer, messages);

    if (!messages.isEmpty()) {
      reviewInput.message(joinWithDoubleNewLine(messages));
    }
    log.debug("System messages for review set: {}", messages);
  }

  private boolean shouldSuppressEmptyReviewMessage(ChangeSetData changeSetData) {
    // A re-review with no incremental code change produces no new issues; do not post the
    // generic "no update" comment in that case.
    String incrementalPatchSet = changeSetData.getIncrementalPatchSet();
    return incrementalPatchSet != null && incrementalPatchSet.isBlank();
  }

  private boolean shouldSuppressSystemMessage(ChangeSetData changeSetData, Integer reviewScore) {
    if (reviewScore == null
        || changeSetData.getReviewSystemMessage() != null
        || changeSetData.getReviewRepeatedCommentsMessage() != null) {
      return false;
    }
    Integer existingReviewScore = getCurrentCodeReviewValue(changeSetData);
    return existingReviewScore == null || !existingReviewScore.equals(reviewScore);
  }

  private Integer getCurrentCodeReviewValue(ChangeSetData changeSetData) {
    try {
      return new GerritClientDetail(config, changeSetData).getCodeReviewValue(change);
    } catch (RuntimeException e) {
      log.warn(
          "Could not determine current Code-Review value for change {}",
          change.getFullChangeId(),
          e);
      return null;
    }
  }

  private Map<String, List<CommentInput>> getReviewComments(List<ReviewBatch> reviewBatches) {
    log.debug("Getting review comments.");
    Map<String, List<CommentInput>> comments = new HashMap<>();
    for (ReviewBatch reviewBatch : reviewBatches) {
      String message = sanitizeAiMessage(reviewBatch.getContent());
      if (message.trim().isEmpty()) {
        log.info(
            "Empty message from review not submitted for batch with ID: {}", reviewBatch.getId());
        continue;
      }
      boolean unresolved;
      String filename = reviewBatch.getFilename();
      List<CommentInput> filenameComments = comments.getOrDefault(filename, new ArrayList<>());
      CommentInput filenameComment = new CommentInput();
      filenameComment.message = message;
      if (reviewBatch.getLine() != null || reviewBatch.getRange() != null) {
        filenameComment.line = reviewBatch.getLine();
        Optional.ofNullable(reviewBatch.getRange())
            .ifPresent(
                r -> {
                  Comment.Range range = new Comment.Range();
                  range.startLine = r.startLine;
                  range.startCharacter = r.startCharacter;
                  range.endLine = r.endLine;
                  range.endCharacter = r.endCharacter;
                  filenameComment.range = range;
                  log.debug(
                      "Setting range for comment on file '{}': startLine {}, endLine {}",
                      filename,
                      range.startLine,
                      range.endLine);
                });
        unresolved = !config.getInlineCommentsAsResolved();
        log.debug("Comment for file '{}' is marked as unresolved: {}", filename, unresolved);
      } else {
        unresolved = !config.getPatchSetCommentsAsResolved();
        log.debug(
            "Patch set comment for file '{}' is marked as unresolved: {}", filename, unresolved);
      }
      filenameComment.inReplyTo = reviewBatch.getId();
      filenameComment.unresolved = unresolved;
      filenameComments.add(filenameComment);
      comments.putIfAbsent(filename, filenameComments);
    }
    log.debug("Review comments processed.");
    return comments;
  }

  private boolean isOpenReviewAiRootThread(
      CommentInfo root, Map<String, List<CommentInfo>> commentsByParentId) {
    if (!PublishedCommentConcernBinder.isTaggedConcernComment(root) || root.inReplyTo != null) {
      return false;
    }

    Comparator<CommentInfo> threadOrder =
        Comparator.comparing(
                (CommentInfo comment) -> comment.updated,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(
                comment -> comment.id, Comparator.nullsFirst(Comparator.naturalOrder()));
    Queue<CommentInfo> unvisited = new PriorityQueue<>(threadOrder);
    Set<String> visitedCommentIds = new HashSet<>();
    CommentInfo latest = root;
    unvisited.add(root);
    while (!unvisited.isEmpty()) {
      CommentInfo comment = unvisited.remove();
      if (comment.id != null && !visitedCommentIds.add(comment.id)) {
        continue;
      }
      latest = comment;
      unvisited.addAll(commentsByParentId.getOrDefault(comment.id, List.of()));
    }
    return Boolean.TRUE.equals(latest.unresolved);
  }

  private String resolutionMessage(ReviewConcern concern) {
    String reason = concern.getStatusReason();
    String fallback =
        switch (concern.getStatus()) {
          case FIXED -> localizer.getText("message.review.concern.resolution.fixed");
          case DISMISSED -> localizer.getText("message.review.concern.resolution.dismissed");
          case SKIPPED -> localizer.getText("message.review.concern.resolution.skipped");
          case PRESENT, UNCERTAIN ->
              throw new IllegalArgumentException(
                  "Cannot resolve concern with status " + concern.getStatus());
        };
    return String.format(
        localizer.getText("message.review.concern.resolution"),
        concern.getStatus(),
        reason == null || reason.isBlank() ? fallback : reason);
  }
}
