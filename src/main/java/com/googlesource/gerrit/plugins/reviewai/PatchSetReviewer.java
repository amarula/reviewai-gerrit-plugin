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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.messages.debug.DebugCodeBlocksReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.comment.GerritCommentRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename.FilenameSanitizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
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

  private GerritCommentRange gerritCommentRange;
  private List<ReviewBatch> reviewBatches;
  private List<GerritComment> commentProperties;
  private List<Integer> reviewScores;

  @Inject
  PatchSetReviewer(
      GerritClient gerritClient,
      Configuration config,
      ChangeSetData changeSetData,
      Provider<GerritClientReview> clientReviewProvider,
      IAiClient openAiClient,
      Localizer localizer) {
    this.config = config;
    this.gerritClient = gerritClient;
    this.changeSetData = changeSetData;
    this.clientReviewProvider = clientReviewProvider;
    this.openAiClient = openAiClient;
    this.localizer = localizer;
    debugCodeBlocksReview = new DebugCodeBlocksReview(localizer);
    log.debug("PatchSetReviewer initialized.");
  }

  public void review(GerritChange change) throws Exception {
    log.debug("Starting review process for change: {}", change.getFullChangeId());
    reviewBatches = new ArrayList<>();
    reviewScores = new ArrayList<>();
    commentProperties = gerritClient.getClientData(change).getCommentProperties();
    gerritCommentRange = new GerritCommentRange(gerritClient, change);
    String patchSet = gerritClient.getPatchSet(change);
    ChangeSetDataHandler.update(config, change, gerritClient, changeSetData, localizer);

    if (changeSetData.shouldRequestAiReview()) {
      AiResponseContent reviewReply = null;
      try {
        reviewReply = getReviewReply(change, patchSet);
        log.debug("OpenAI final response: {}", reviewReply);
      } catch (AiConnectionFailException e) {
        log.error(
            "OpenAI request failed for change `{}`. domain=`{}`, model=`{}`, requestBody={}. Cause: {}",
            change.getFullChangeId(),
            config.getAiDomain(),
            config.getAiModel(),
            openAiClient.getRequestBody() == null ? "<unavailable>" : openAiClient.getRequestBody(),
            e.getMessage(),
            e);
        changeSetData.setReviewSystemMessage(localizer.getText("message.openai.connection.error"));
      }
      if (reviewReply != null) {
        retrieveReviewBatches(reviewReply, change);
      }
    }
    clientReviewProvider
        .get()
        .setReview(change, reviewBatches, changeSetData, getReviewScore(change));
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
      Integer score = replyItem.getScore();
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
    if (config.isVotingEnabled()) {
      if (change.getIsCommentEvent()) {
        return null;
      }
      Integer reviewScore = reviewScores.isEmpty() ? 0 : Collections.min(reviewScores);
      if (reviewScore == 0
          && config.getConvertNeutralReviewScoreToPositive()
          && changeSetData.getVotingMaxScore() >= 1) {
        return 1;
      }
      return reviewScore;
    } else {
      return null;
    }
  }

  private boolean isNotNegativeReply(Integer score) {
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
