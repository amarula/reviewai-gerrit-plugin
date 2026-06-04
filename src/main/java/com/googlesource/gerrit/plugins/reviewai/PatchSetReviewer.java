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

package com.googlesource.gerrit.plugins.reviewai;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ChangeSetDataHandler;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.comment.GerritCommentRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename.FilenameSanitizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class PatchSetReviewer {
  private static final String SPLIT_REVIEW_MSG =
      "Too many changes. Please consider splitting into patches smaller "
          + "than %s lines for review.";

  private final Configuration config;
  private final GerritClient gerritClient;
  private final ChangeSetData changeSetData;
  private final Provider<GerritClientReview> clientReviewProvider;
  @Getter private final IAiClient openAiClient;
  private final Localizer localizer;
  private final DebugCodeBlocksReview debugCodeBlocksReview;
  private final PatchSetReviewConversationRecorder conversationRecorder;

  private GerritCommentRange gerritCommentRange;
  private List<ReviewBatch> reviewBatches;
  private List<GerritComment> commentProperties;
  private List<Double> reviewScores;

  @Inject
  PatchSetReviewer(
      GerritClient gerritClient,
      Configuration config,
      ChangeSetData changeSetData,
      Provider<GerritClientReview> clientReviewProvider,
      IAiClient openAiClient,
      Localizer localizer,
      PatchSetReviewConversationRecorder conversationRecorder) {
    this.config = config;
    this.gerritClient = gerritClient;
    this.changeSetData = changeSetData;
    this.clientReviewProvider = clientReviewProvider;
    this.openAiClient = openAiClient;
    this.localizer = localizer;
    this.conversationRecorder = conversationRecorder;
    debugCodeBlocksReview = new DebugCodeBlocksReview(localizer);
    log.debug("PatchSetReviewer initialized.");
  }

  public void review(GerritChange change) throws Exception {
    log.debug("Starting review process for change: {}", change.getFullChangeId());
    reviewBatches = new ArrayList<>();
    reviewScores = new ArrayList<>();
    if (!changeSetData.shouldRequestAiReview()) {
      log.debug("Skipping patch retrieval and AI request because only a system response is needed.");
      clientReviewProvider.get().setReview(change, reviewBatches, changeSetData, null);
      return;
    }
    commentProperties = gerritClient.getClientData(change).getCommentProperties();
    gerritCommentRange = new GerritCommentRange(gerritClient, change);
    String patchSet = gerritClient.getPatchSet(change);
    if (shouldSkipAiReviewForEmptyPatchSet(change)) {
      log.debug(
          "Skipping AI review for change {} because no files remain after patch filtering.",
          change.getFullChangeId());
      if (change.getIsCommentEvent() || changeSetData.getForcedReview()) {
        clientReviewProvider.get().setReview(change, reviewBatches, changeSetData, null);
      }
      return;
    }
    ChangeSetDataHandler.update(config, change, gerritClient, changeSetData, localizer);

    AiResponseContent reviewReply = null;
    try {
      reviewReply = getReviewReply(change, patchSet);
      log.debug("AI final response: {}", reviewReply);
    } catch (AiConnectionFailException e) {
      log.error(
          "AI request failed for change `{}`. domain=`{}`, model=`{}`, requestBody={}. Cause: {}",
          change.getFullChangeId(),
          config.getAiDomain(),
          config.getAiModel(),
          openAiClient.getRequestBody() == null ? "<unavailable>" : openAiClient.getRequestBody(),
          e.getMessage(),
          e);
      changeSetData.setReviewSystemMessage(
          SystemMessageFormatter.getLocalizedErrorMessage(
              localizer, "message.openai.connection.error"));
    }
    if (reviewReply != null) {
      retrieveReviewBatches(reviewReply, change);
    }
    Integer reviewScore = getReviewScore(change);
    clientReviewProvider.get().setReview(change, reviewBatches, changeSetData, reviewScore);
    conversationRecorder.record(change, reviewBatches, reviewScore);
  }

  private boolean shouldSkipAiReviewForEmptyPatchSet(GerritChange change) {
    if (changeSetData.getReviewScope() == ReviewScope.COMMIT_MESSAGE) {
      return false;
    }
    List<String> patchSetFiles =
        gerritClient.getClientData(change).getGerritClientPatchSet().getPatchSetFiles();
    return patchSetFiles == null || patchSetFiles.isEmpty();
  }

  private void setCommentBatchMap(ReviewBatch batchMap, Integer batchID) {
    if (commentProperties != null && batchID < commentProperties.size()) {
      GerritComment commentProperty = commentProperties.get(batchID);
      if (commentProperty != null) {
        batchMap.setId(commentProperty.getId());
        batchMap.setFilename(commentProperty.getFilename());
        batchMap.setLine(commentProperty.getLine());
        if (commentProperty.getRange() != null) {
          batchMap.setRange(commentProperty.getRange());
        }
      }
    }
  }

  private void setPatchSetReviewBatchMap(ReviewBatch batchMap, AiReplyItem replyItem) {
    Optional<GerritCodeRange> optGerritCommentRange =
        gerritCommentRange.getGerritCommentRange(replyItem);
    if (optGerritCommentRange.isPresent()) {
      GerritCodeRange gerritCodeRange = optGerritCommentRange.get();
      batchMap.setFilename(replyItem.getFilename());
      batchMap.setLine(gerritCodeRange.getStartLine());
      batchMap.setRange(gerritCodeRange);
    }
  }

  private void retrieveReviewBatches(AiResponseContent reviewReply, GerritChange change) {
    FilenameSanitizer filenameSanitizer = new FilenameSanitizer(gerritClient, change);
    log.debug("Retrieving review batches for change: {}", change.getFullChangeId());
    if (reviewReply.getMessageContent() != null && !reviewReply.getMessageContent().isEmpty()) {
      reviewBatches.add(new ReviewBatch(reviewReply.getMessageContent()));
      log.debug("Added single message content to review batches.");
      return;
    }
    for (AiReplyItem replyItem : reviewReply.getReplies()) {
      String reply = replyItem.getReply();
      Double score = replyItem.getScore();
      boolean isNotNegative = isNotNegativeReply(score);
      boolean isIrrelevant = isIrrelevantReply(replyItem);
      boolean isHidden =
          replyItem.isRepeated() || replyItem.isConflicting() || isIrrelevant || isNotNegative;
      if (!replyItem.isConflicting() && !isIrrelevant && score != null) {
        log.debug("Score added: {}", score);
        reviewScores.add(score);
      }
      if (reply == null
          || !change.getIsCommentEvent() && changeSetData.getReplyFilterEnabled() && isHidden) {
        continue;
      }
      if (changeSetData.getDebugReviewMode()) {
        reply += debugCodeBlocksReview.getDebugCodeBlock(replyItem, isHidden);
      }
      ReviewBatch batchMap = new ReviewBatch(reply);
      if (change.getIsCommentEvent() && replyItem.getId() != null) {
        setCommentBatchMap(batchMap, replyItem.getId());
      } else {
        filenameSanitizer.sanitizeFilename(replyItem);
        setPatchSetReviewBatchMap(batchMap, replyItem);
      }
      reviewBatches.add(batchMap);
      log.debug("Added review batch from reply item: {}", batchMap);
    }
  }

  private AiResponseContent getReviewReply(GerritChange change, String patchSet)
      throws Exception {
    log.debug("Generating review reply for patch set.");
    List<String> patchLines = Arrays.asList(patchSet.split("\n"));
    if (patchLines.size() > config.getMaxReviewLines()) {
      log.warn(
          "Patch set too large for review, size: {}, max allowed: {}",
          patchLines.size(),
          config.getMaxReviewLines());
      return new AiResponseContent(String.format(SPLIT_REVIEW_MSG, config.getMaxReviewLines()));
    }

    return openAiClient.ask(changeSetData, change, patchSet);
  }

  private Integer getReviewScore(GerritChange change) {
    log.debug("Calculating review score for change ID: {}", change.getFullChangeId());
    if (changeSetData.getSuggestMode()) {
      return null;
    }
    if (config.isVotingEnabled()) {
      if (change.getIsCommentEvent()) {
        return null;
      }
      int reviewScore =
          reviewScores.isEmpty() ? 0 : normalizeReviewScore(Collections.min(reviewScores));
      if (reviewScore == 0
          && config.getConvertNeutralReviewScoreToPositive()
          && changeSetData.getVotingMaxScore() >= 1) {
        reviewScore = 1;
      }
      if (reviewScore > 0 && isPartialReview()) {
        changeSetData.setReviewNoticeMessage(
            localizer.getText("message.review.partial.positive.score.skipped"));
        return null;
      }
      return reviewScore;
    } else {
      return null;
    }
  }

  private boolean isPartialReview() {
    return changeSetData.getReviewScope() == ReviewScope.PATCHSET
        || changeSetData.getReviewScope() == ReviewScope.COMMIT_MESSAGE;
  }

  private int normalizeReviewScore(double score) {
    // Gerrit labels are integers. Keep decimal scores for filtering, but normalize the
    // aggregated vote to the configured integer range at submission time.
    return Math.clamp((int) Math.floor(score),
        changeSetData.getVotingMinScore(), changeSetData.getVotingMaxScore());
  }

  private boolean isNotNegativeReply(Double score) {
    return score != null
        && config.getFilterNegativeComments()
        && score >= config.getFilterCommentsBelowScore();
  }

  private boolean isIrrelevantReply(AiReplyItem replyItem) {
    return config.getFilterRelevantComments()
        && replyItem.getRelevance() != null
        && replyItem.getRelevance() < config.getFilterCommentsRelevanceThreshold();
  }
}
