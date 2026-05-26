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
import com.google.gerrit.extensions.client.Comment;
import com.google.gerrit.entities.LabelId;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewInput.CommentInput;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManager;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.ErrorMessageHandler;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksDynamicConfiguration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.MessageSanitizer.sanitizeAiMessage;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

@Slf4j
public class GerritClientReview extends GerritClientAccount {
  private final PluginDataHandlerProvider pluginDataHandlerProvider;
  private final Localizer localizer;
  private final DebugCodeBlocksDynamicConfiguration debugCodeBlocksDynamicConfiguration;
  private final ErrorMessageHandler errorMessageHandler;

  private GerritChange change;

  @VisibleForTesting
  @Inject
  public GerritClientReview(
      Configuration config,
      AccountCache accountCache,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer) {
    super(config, accountCache);
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.localizer = localizer;
    debugCodeBlocksDynamicConfiguration = new DebugCodeBlocksDynamicConfiguration(localizer);
    errorMessageHandler = new ErrorMessageHandler(config, localizer);
    log.debug("GerritClientReview initialized.");
  }

  public void setReview(
      GerritChange change,
      List<ReviewBatch> reviewBatches,
      ChangeSetData changeSetData,
      Integer reviewScore)
      throws Exception {
    log.debug("Setting review for change ID: {}", change.getFullChangeId());
    this.change = change;
    ReviewInput reviewInput = buildReview(reviewBatches, changeSetData, reviewScore);
    if (reviewInput.comments == null && reviewInput.message == null && reviewInput.labels == null) {
      log.debug("No comments, messages, or labels to post for review.");
      return;
    }
    try (ManualRequestContext requestContext = config.openRequestContext()) {
      ReviewResult result =
          config
              .getGerritApi()
              .changes()
              .id(
                  change.getProjectName(),
                  change.getBranchNameKey().shortName(),
                  change.getChangeKey().get())
              .current()
              .review(reviewInput);

      if (!Strings.isNullOrEmpty(result.error)) {
        log.error("Review setting failed with status code: {}", result.error);
      }
    }
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
    } else if (!changeSetData.shouldHideOpenAiReview()) {
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
    if (!change.getIsCommentEvent() && !changeSetData.getHideDynamicConfigMessage()) {
      Map<String, String> dynamicConfig =
          new DynamicConfigManager(pluginDataHandlerProvider).getDynamicConfigForDisplay(config);
      if (dynamicConfig != null && !dynamicConfig.isEmpty()) {
        messages.add(debugCodeBlocksDynamicConfiguration.getDebugCodeBlock(dynamicConfig));
      }
    }
    if (changeSetData.getReviewNoticeMessage() != null) {
      messages.add(
          localizer.getText("system.message.prefix")
              + ' '
              + changeSetData.getReviewNoticeMessage());
    }
    if (emptyComments) {
      messages.add(localizer.getText("system.message.prefix") + ' ' + systemMessage);
    }
    errorMessageHandler.updateErrorMessages(messages);

    if (!messages.isEmpty()) {
      reviewInput.message(joinWithDoubleNewLine(messages));
    }
    log.debug("System messages for review set: {}", messages);
  }

  private boolean shouldSuppressSystemMessage(ChangeSetData changeSetData, Integer reviewScore) {
    if (reviewScore == null || changeSetData.getReviewSystemMessage() != null) {
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
}
