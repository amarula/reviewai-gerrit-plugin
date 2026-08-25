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

package com.googlesource.gerrit.plugins.reviewai.review;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ChangeSetDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.listener.AiReviewApplicabilityChecker;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.review.RepeatedCommentReferenceFormatter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.comment.GerritCommentRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename.FilenameSanitizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPermittedVotingRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import com.googlesource.gerrit.plugins.reviewai.review.topic.TopicReviewReplyMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import javax.annotation.Nullable;

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
  private final RepeatedCommentReferenceFormatter repeatedCommentReferenceFormatter;
  private final TopicPatchSetReviewer topicPatchSetReviewer;
  private final TopicReviewReplyMapper topicReviewReplyMapper;
  private final ReviewConcernPublisher reviewConcernPublisher;
  private final ReviewFeedbackLifecycle reviewFeedbackLifecycle;
  private final AiReviewApplicabilityChecker aiReviewApplicabilityChecker;

  private GerritCommentRange gerritCommentRange;
  private List<ReviewBatch> reviewBatches;
  private List<GerritComment> commentProperties;
  private List<Double> reviewScores = new ArrayList<>();

  @Inject
  public PatchSetReviewer(
      GerritClient gerritClient,
      Configuration config,
      ChangeSetData changeSetData,
      Provider<GerritClientReview> clientReviewProvider,
      IAiClient openAiClient,
      Localizer localizer,
      PatchSetReviewConversationRecorder conversationRecorder,
      ReviewConcernPublisher reviewConcernPublisher,
      ReviewFeedbackLifecycle reviewFeedbackLifecycle,
      AiReviewApplicabilityChecker aiReviewApplicabilityChecker,
      @CanonicalWebUrl @Nullable String canonicalWebUrl) {
    this.config = config;
    this.gerritClient = gerritClient;
    this.changeSetData = changeSetData;
    this.clientReviewProvider = clientReviewProvider;
    this.openAiClient = openAiClient;
    this.localizer = localizer;
    this.conversationRecorder = conversationRecorder;
    this.reviewConcernPublisher = reviewConcernPublisher;
    this.reviewFeedbackLifecycle = reviewFeedbackLifecycle;
    this.aiReviewApplicabilityChecker = aiReviewApplicabilityChecker;
    this.repeatedCommentReferenceFormatter =
        new RepeatedCommentReferenceFormatter(
            gerritClient, changeSetData, localizer, canonicalWebUrl);
    this.topicReviewReplyMapper = new TopicReviewReplyMapper();
    this.topicPatchSetReviewer =
        new TopicPatchSetReviewer(
            config,
            gerritClient,
            changeSetData,
            localizer,
            this);
    debugCodeBlocksReview = new DebugCodeBlocksReview(localizer);
    log.debug("PatchSetReviewer initialized.");
  }

  public void review(GerritChange change) throws Exception {
    review(change, false);
  }

  public void review(GerritChange change, boolean includeAiFailureDetails) throws Exception {
    log.debug("Starting review process for change: {}", change.getFullChangeId());
    reviewBatches = new ArrayList<>();
    reviewScores = new ArrayList<>();
    changeSetData.setReviewRepeatedCommentsMessage(null);
    reviewFeedbackLifecycle.reset(changeSetData);
    if (!changeSetData.shouldRequestAiReview()) {
      log.debug("Skipping patch retrieval and AI request because only a system response is needed.");
      clientReviewProvider.get().setReview(change, reviewBatches, changeSetData, null);
      return;
    }
    commentProperties = gerritClient.getClientData(change).getCommentProperties();
    gerritCommentRange = new GerritCommentRange(gerritClient, change);
    String patchSet = gerritClient.getPatchSet(change);
    prepareConcernContext(change);
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
    ReviewFeedbackLifecycle.Session feedbackSession =
        reviewFeedbackLifecycle.begin(change, changeSetData);

    AiResponseContent reviewReply = null;
    try {
      reviewReply = getReviewReply(change, patchSet);
      log.debug("AI final response: {}", reviewReply);
    } catch (Exception e) {
      log.error(
          "AI request failed for change `{}`. domain=`{}`, model=`{}`, requestBody={}. Cause: {}",
          change.getFullChangeId(),
          config.getAiDomain(),
          config.getAiModel(),
          openAiClient.getRequestBody() == null ? "<unavailable>" : openAiClient.getRequestBody(),
          e.getMessage(),
          e);
      String publicErrorMessage =
          SystemMessageFormatter.getLocalizedErrorMessage(
              localizer, "message.openai.connection.error");
      changeSetData.setReviewSystemMessage(publicErrorMessage);
      changeSetData.setReviewStatusMessage(
          includeAiFailureDetails
              ? SystemMessageFormatter.getLocalizedErrorMessageWithReason(
                  localizer, "message.openai.connection.error", e)
              : publicErrorMessage);
    }
    if (reviewReply != null) {
      reviewBatches = retrieveReviewBatches(reviewReply, change);
    }
    Integer reviewScore = getReviewScore(change);
    Map<String, String> publishedCommentIdsByConcern;
    try {
      publishedCommentIdsByConcern =
          clientReviewProvider
              .get()
              .setReviewAndGetPublishedCommentIds(
                  change, reviewBatches, changeSetData, reviewScore);
      reviewConcernPublisher.persist(reviewReply, change, publishedCommentIdsByConcern);
      clientReviewProvider.get().resolveInactiveConcernThreads(change, reviewReply);
      reviewFeedbackLifecycle.settle(
          change, changeSetData, feedbackSession, reviewReply != null);
      conversationRecorder.record(change, reviewBatches, reviewScore);
    } catch (Exception e) {
      reviewFeedbackLifecycle.release(change, feedbackSession, e);
      throw e;
    }
  }

  private void prepareConcernContext(GerritChange change) throws Exception {
    changeSetData.setPreviousReviewConcernLedger(null);
    changeSetData.setIncrementalPatchSet(null);
    var existingLedger = reviewConcernPublisher.load(change);
    if (existingLedger.isPresent()) {
      changeSetData.setPreviousReviewConcernLedger(existingLedger.get());
      changeSetData.setIncrementalPatchSet(gerritClient.getIncrementalPatchSet(change));
    }
    reviewFeedbackLifecycle.loadMemory(change, changeSetData);
  }

  public void reviewTopic(List<GerritChange> changes, boolean includeAiFailureDetails)
      throws Exception {
    topicPatchSetReviewer.review(changes, includeAiFailureDetails);
  }

  boolean shouldSkipAiReviewForEmptyPatchSet(GerritChange change) {
    if (changeSetData.getReviewScope() == ReviewScope.COMMIT_MESSAGE) {
      return false;
    }
    List<String> patchSetFiles =
        gerritClient.getClientData(change).getGerritClientPatchSet().getPatchSetFiles();
    return patchSetFiles == null || patchSetFiles.isEmpty();
  }

  void publishTopicReviewPart(
      AiResponseContent reviewReply,
      GerritChange change,
      String topicFilenamePrefix,
      List<Double> topicReviewScores)
      throws Exception {
    reviewBatches = new ArrayList<>();
    reviewScores = new ArrayList<>();
    changeSetData.setReviewNoticeMessage(null);
    changeSetData.setReviewRepeatedCommentsMessage(null);
    gerritClient.retrievePatchSetInfo(change);
    gerritClient.getPatchSet(change);
    commentProperties = gerritClient.getClientData(change).getCommentProperties();
    gerritCommentRange = new GerritCommentRange(gerritClient, change);
    ChangeSetDataHandler.update(config, change, gerritClient, changeSetData, localizer);
    if (reviewReply != null) {
      reviewBatches = retrieveReviewBatches(reviewReply, change, topicFilenamePrefix);
    }
    Integer reviewScore =
        topicReviewScores == null
            ? getReviewScore(change)
            : getReviewScore(change, topicReviewScores);
    Map<String, String> publishedCommentIdsByConcern =
        clientReviewProvider
            .get()
            .setReviewAndGetPublishedCommentIds(
                change, reviewBatches, changeSetData, reviewScore);
    reviewConcernPublisher.persist(reviewReply, change, publishedCommentIdsByConcern);
    clientReviewProvider.get().resolveInactiveConcernThreads(change, reviewReply);
    conversationRecorder.record(change, reviewBatches, reviewScore);
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
    if (gerritCommentRange == null) {
      return;
    }
    Optional<GerritCodeRange> optGerritCommentRange =
        gerritCommentRange.getGerritCommentRange(replyItem);
    if (optGerritCommentRange.isPresent()) {
      GerritCodeRange gerritCodeRange = optGerritCommentRange.get();
      batchMap.setFilename(replyItem.getFilename());
      batchMap.setLine(gerritCodeRange.getStartLine());
      batchMap.setRange(gerritCodeRange);
    }
  }

  private List<ReviewBatch> retrieveReviewBatches(AiResponseContent reviewReply, GerritChange change) {
    return retrieveReviewBatches(reviewReply, change, null);
  }

  private List<ReviewBatch> retrieveReviewBatches(
      AiResponseContent reviewReply, GerritChange change, String topicFilenamePrefix) {
    List<ReviewBatch> batches = new ArrayList<>();
    FilenameSanitizer filenameSanitizer = new FilenameSanitizer(gerritClient, change);
    List<AiReplyItem> filteredRepeatedReplyItems = new ArrayList<>();
    List<String> debugDetails = new ArrayList<>();
    log.debug("Retrieving review batches for change: {}", change.getFullChangeId());
    if (reviewReply.getMessageContent() != null && !reviewReply.getMessageContent().isEmpty()) {
      batches.add(new ReviewBatch(reviewReply.getMessageContent()));
      log.debug("Added single message content to review batches.");
      return batches;
    }
    for (AiReplyItem replyItem : reviewReply.getReplies()) {
      Optional<AiReplyItem> topicReplyItem =
          topicReviewReplyMapper.replyForChange(replyItem, topicFilenamePrefix);
      if (topicReplyItem.isEmpty()) {
        continue;
      }
      replyItem = topicReplyItem.get();
      String reply = replyItem.getReply();
      Double score = replyItem.getScore();
      boolean isIrrelevant = isIrrelevantReply(replyItem);
      boolean isHidden =
          replyItem.isRepeated()
              || replyItem.isDuplicated()
              || replyItem.isConflicting()
              || isIrrelevant;
      boolean hiddenByReplyFilter =
          !change.getIsCommentEvent() && changeSetData.getReplyFilterEnabled() && isHidden;
      if (hiddenByReplyFilter && replyItem.isRepeated() && !isIrrelevant) {
        filteredRepeatedReplyItems.add(replyItem);
      }
      if (isScoredReply(replyItem, isIrrelevant) && score != null) {
        log.debug("Score added: {}", score);
        reviewScores.add(score);
      }
      if (reply == null
          || hiddenByReplyFilter) {
        continue;
      }
      if (changeSetData.getDebugReviewMode()) {
        debugDetails.add(debugCodeBlocksReview.getDebugCodeBlock(replyItem, isHidden));
      }
      ReviewBatch batchMap = new ReviewBatch(reply);
      batchMap.setConcernId(replyItem.getConcernId());
      if (change.getIsCommentEvent() && replyItem.getId() != null) {
        setCommentBatchMap(batchMap, replyItem.getId());
      } else {
        filenameSanitizer.sanitizeFilename(replyItem);
        setPatchSetReviewBatchMap(batchMap, replyItem);
      }
      batches.add(batchMap);
      log.debug("Added review batch from reply item: {}", batchMap);
    }
    if (!debugDetails.isEmpty()) {
      changeSetData.setReviewStatusMessage(String.join("\n\n", debugDetails));
    }
    setRepeatedCommentsMessage(filteredRepeatedReplyItems, change);
    return batches;
  }

  List<Double> getReviewScores(AiResponseContent reviewReply) {
    if (reviewReply == null || reviewReply.getReplies() == null) {
      return List.of();
    }
    List<Double> scores = new ArrayList<>();
    for (AiReplyItem replyItem : reviewReply.getReplies()) {
      boolean isIrrelevant = isIrrelevantReply(replyItem);
      Double score = replyItem.getScore();
      if (isScoredReply(replyItem, isIrrelevant) && score != null) {
        scores.add(score);
      }
    }
    return scores;
  }

  private void setRepeatedCommentsMessage(
      List<AiReplyItem> filteredRepeatedReplyItems, GerritChange change) {
    repeatedCommentReferenceFormatter
        .format(filteredRepeatedReplyItems, change)
        .ifPresent(changeSetData::setReviewRepeatedCommentsMessage);
  }

  AiResponseContent getReviewReply(GerritChange change, String patchSet)
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

    boolean aiReviewConditionMet =
        !changeSetData.getForcedReview()
            || aiReviewApplicabilityChecker.isApplicable(change, config.getAiReviewApplicableIf());
    changeSetData.setAiReviewConditionMet(aiReviewConditionMet);
    changeSetData.setConditionLabels(
        aiReviewConditionMet
            ? gerritClient.getConditionLabels(change, config.getAiReviewApplicableIf())
            : Map.of());
    return openAiClient.ask(changeSetData, change, patchSet);
  }

  private Integer getReviewScore(GerritChange change) {
    return getReviewScore(change, reviewScores);
  }

  private Integer getReviewScore(GerritChange change, List<Double> scores) {
    log.debug("Calculating review score for change ID: {}", change.getFullChangeId());
    if (changeSetData.getSuggestMode()) {
      return null;
    }
    if (config.isVotingEnabled()) {
      if (change.getIsCommentEvent()) {
        return null;
      }
      int reviewScore = scores.isEmpty() ? 0 : normalizeReviewScore(Collections.min(scores));
      if (reviewScore == 0
          && config.getConvertNeutralReviewScoreToPositive()
          && canVotePositive()) {
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
    // Gerrit labels are integers. Keep decimal scores in replies, but normalize the aggregated
    // vote to the permitted Gerrit range at submission time when it is available.
    int normalizedScore = (int) Math.floor(score);
    GerritPermittedVotingRange permittedVotingRange = changeSetData.getPermittedVotingRange();
    if (permittedVotingRange == null) {
      return normalizedScore;
    }
    return Math.clamp(normalizedScore, permittedVotingRange.getMin(), permittedVotingRange.getMax());
  }

  private boolean canVotePositive() {
    GerritPermittedVotingRange permittedVotingRange = changeSetData.getPermittedVotingRange();
    return permittedVotingRange == null || permittedVotingRange.getMax() >= 1;
  }

  private boolean isIrrelevantReply(AiReplyItem replyItem) {
    return replyItem.getRelevance() != null
        && replyItem.getRelevance() < config.getFilterCommentsRelevanceThreshold();
  }

  private boolean isScoredReply(AiReplyItem replyItem, boolean isIrrelevant) {
    return !replyItem.isDuplicated() && !replyItem.isConflicting() && !isIrrelevant;
  }
}
