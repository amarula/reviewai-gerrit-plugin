/*
 * Copyright (c) 2026. The Android Open Source Project
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

package com.googlesource.gerrit.plugins.reviewai.review;

import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.inject.Inject;
import com.google.inject.Provider;
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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernDismissal;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ChangeSetDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.listener.AiReviewApplicabilityChecker;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.review.topic.TopicReviewReplyMapper;
import com.googlesource.gerrit.plugins.reviewai.utils.DiffStats;
import java.util.*;
import javax.annotation.Nullable;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class PatchSetReviewer {
  private static final String SPLIT_REVIEW_MSG =
      "Too many changes. Please consider splitting into patches smaller "
          + "than %s changed lines for review.";

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
        new TopicPatchSetReviewer(config, gerritClient, changeSetData, localizer, this);
    debugCodeBlocksReview = new DebugCodeBlocksReview(localizer);
    log.debug("PatchSetReviewer initialized.");
  }

  public void review(GerritChange change) throws Exception {
    review(change, false);
  }

  public void review(GerritChange change, boolean includeAiFailureDetails) throws Exception {
    log.debug("Starting review process for change: {}", change.getFullChangeId());
    gerritClient.requireCurrentRevision(change);
    reviewBatches = new ArrayList<>();
    reviewScores = new ArrayList<>();
    changeSetData.setReviewRepeatedCommentsMessage(null);
    reviewFeedbackLifecycle.reset(changeSetData);
    if (!changeSetData.shouldRequestAiReview()) {
      log.debug(
          "Skipping patch retrieval and AI request because only a system response is needed.");
      clientReviewProvider.get().setReview(change, reviewBatches, changeSetData, null);
      return;
    }
    commentProperties = gerritClient.getClientData(change).getCommentProperties();
    gerritCommentRange = new GerritCommentRange(gerritClient, change);
    String patchSet = gerritClient.getPatchSet(change);
    prepareConcernContext(change);
    if (shouldSkipAiReviewForEmptyPatchSet(change)) {
      changeSetData.setReviewSystemMessage(
          SystemMessageFormatter.getLocalizedMessage(localizer, "message.review.skipped"));
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
    } catch (AiRequestSupersededException e) {
      reviewFeedbackLifecycle.release(change, feedbackSession, e);
      throw e;
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
    if (reviewReply == null && changeSetData.getReviewSystemMessage() == null) {
      reviewFeedbackLifecycle.settle(change, changeSetData, feedbackSession, false);
      log.debug("Skipping Gerrit review publication because no AI review was performed.");
      return;
    }
    if (reviewReply != null) {
      reviewBatches = retrieveReviewBatches(reviewReply, change);
      dismissConcernsWithoutFiles(reviewReply, change);
    }
    Integer reviewScore = getReviewScore(change, reviewReply);
    Map<String, String> publishedCommentIdsByConcern;
    try {
      publishedCommentIdsByConcern =
          reviewClientFor(change)
              .setReviewAndGetPublishedCommentIds(
                  change, reviewBatches, changeSetData, reviewScore, reviewReply);
      reviewConcernPublisher.persist(reviewReply, change, publishedCommentIdsByConcern);
      reviewFeedbackLifecycle.settle(change, changeSetData, feedbackSession, reviewReply != null);
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
    if (change.getIsCommentEvent()
        || changeSetData.getReviewScope() == ReviewScope.COMMIT_MESSAGE) {
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
      dismissConcernsWithoutFiles(reviewReply, change);
    }
    Integer reviewScore =
        reviewReply == null
            ? null
            : getReviewScore(
                change, topicReviewScores == null ? reviewScores : topicReviewScores, reviewReply);
    Map<String, String> publishedCommentIdsByConcern =
        reviewClientFor(change)
            .setReviewAndGetPublishedCommentIds(
                change, reviewBatches, changeSetData, reviewScore, reviewReply);
    reviewConcernPublisher.persist(reviewReply, change, publishedCommentIdsByConcern);
    conversationRecorder.record(change, reviewBatches, reviewScore);
  }

  /**
   * Re-anchors a reply to another location of its concern, when the one it names has left.
   *
   * <p>A concern records every location it was raised at, and a rewritten change can drop some of
   * them while others survive. The reply names the first location, which need not be a surviving
   * one, so without this a concern that still points at real code would be demoted to a
   * patch-set-level comment merely because its first location went away.
   *
   * @return whether the reply now names a file in this revision
   */
  private boolean retargetToSurvivingLocation(
      AiReplyItem replyItem,
      AiResponseContent reviewReply,
      GerritChange change,
      FilenameSanitizer filenameSanitizer) {
    String filename = replyItem.getFilename();
    if (filename == null
        || filename.isEmpty()
        || filenameSanitizer.isPartOfPatchSet(filename)
        || reviewReply.getPendingConcernUpdates() == null) {
      return false;
    }
    String concernId = replyItem.getConcernId();
    if (concernId == null || concernId.isBlank()) {
      return false;
    }
    Optional<ConcernLocation> survivingLocation =
        reviewReply.getPendingConcernUpdates().get(change.getFullChangeId()).stream()
            .flatMap(ledger -> ledger.getReviewers().stream())
            .flatMap(reviewer -> reviewer.getConcerns().stream())
            .filter(concern -> concernId.equals(concern.getId()))
            .flatMap(concern -> concern.getLocations().stream())
            .filter(location -> filenameSanitizer.isPartOfPatchSet(location.getFilename()))
            .findFirst();
    if (survivingLocation.isEmpty()) {
      return false;
    }
    ConcernLocation location = survivingLocation.get();
    log.debug("Concern {} moved from '{}' to '{}'", concernId, filename, location.getFilename());
    replyItem.setFilename(location.getFilename());
    replyItem.setLineNumber(location.getLineNumber());
    replyItem.setCodeSnippet(location.getCodeSnippet());
    return true;
  }

  /**
   * Closes concerns whose code this revision no longer contains, before anything reads the ledger.
   *
   * <p>Runs before the score is computed as well as before publication: a concern closed here is
   * not an open one when the vote is decided, and the ledger the publisher stores is the one the
   * next review reasons from. In the plain case the reviewer itself notices a vanished file; this
   * is for the one it cannot, where it keeps insisting on code that is not there.
   */
  void dismissConcernsWithoutFiles(AiResponseContent reviewReply, GerritChange change) {
    PendingReviewConcernUpdates pendingUpdates = reviewReply.getPendingConcernUpdates();
    if (pendingUpdates == null) {
      return;
    }
    FilenameSanitizer filenameSanitizer = new FilenameSanitizer(gerritClient, change);
    String reason = localizer.getText("message.review.concern.resolution.file.removed");
    pendingUpdates
        .get(change.getFullChangeId())
        .map(
            ledger ->
                ReviewConcernDismissal.dismissConcernsWithoutFiles(
                    ledger, filenameSanitizer::isPartOfPatchSet, reason))
        .ifPresent(ledger -> pendingUpdates.replace(change.getFullChangeId(), ledger));
  }

  /**
   * Returns the review client, told which files this revision contains.
   *
   * <p>Every publication path goes through here. The list is always reset, so a failed lookup
   * cannot leave the previous change's files behind on a client that is reused across changes; a
   * lookup that fails yields an empty list, which the client reads as "unknown" and accepts
   * everything. A guard that cannot see the file list must not be the thing that breaks the review.
   */
  private GerritClientReview reviewClientFor(GerritChange change) {
    GerritClientReview clientReview = clientReviewProvider.get();
    List<String> patchSetFiles = List.of();
    try {
      patchSetFiles =
          gerritClient.getClientData(change).getGerritClientPatchSet().getPatchSetFiles();
    } catch (RuntimeException e) {
      log.warn(
          "Could not read the patch set files for {}; publishing without path validation",
          change.getFullChangeId(),
          e);
    }
    clientReview.setPatchSetFiles(patchSetFiles);
    return clientReview;
  }

  /**
   * Anchors a reply to a comment to the same file and line as the comment it answers.
   *
   * <p>The comment being answered is not necessarily on a file that still exists in this revision -
   * a patch set can drop it - and Gerrit rejects a comment posted against a file outside the
   * revision, failing the whole review. When the file is gone the reply keeps its text but loses
   * the anchor, becoming a patch-set-level comment instead; the author is told which files those
   * were.
   */
  private void setCommentBatchMap(
      ReviewBatch batchMap,
      Integer batchID,
      FilenameSanitizer filenameSanitizer,
      List<String> unanchoredFilenames) {
    if (commentProperties != null && batchID < commentProperties.size()) {
      GerritComment commentProperty = commentProperties.get(batchID);
      if (commentProperty != null) {
        if (!isPartOfCurrentPatchSet(filenameSanitizer, commentProperty.getFilename())) {
          unanchoredFilenames.add(commentProperty.getFilename());
          return;
        }
        batchMap.setId(commentProperty.getId());
        batchMap.setFilename(commentProperty.getFilename());
        batchMap.setLine(commentProperty.getLine());
        if (commentProperty.getRange() != null) {
          batchMap.setRange(commentProperty.getRange());
        }
      }
    }
  }

  /**
   * Whether a file may be named in a comment for this revision.
   *
   * <p>{@link FilenameSanitizer#isPartOfPatchSet} answers {@code true} when the patch file list is
   * unknown, so an unknown list keeps the previous behaviour of anchoring whatever the reply named.
   * That is deliberate: the list is unavailable for comment events, which is exactly the case where
   * replying in-thread is the point.
   */
  private static boolean isPartOfCurrentPatchSet(
      FilenameSanitizer filenameSanitizer, String filename) {
    return filename != null && !filename.isEmpty() && filenameSanitizer.isPartOfPatchSet(filename);
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

  private List<ReviewBatch> retrieveReviewBatches(
      AiResponseContent reviewReply, GerritChange change) {
    return retrieveReviewBatches(reviewReply, change, null);
  }

  private List<ReviewBatch> retrieveReviewBatches(
      AiResponseContent reviewReply, GerritChange change, String topicFilenamePrefix) {
    List<ReviewBatch> batches = new ArrayList<>();
    FilenameSanitizer filenameSanitizer = new FilenameSanitizer(gerritClient, change);
    List<AiReplyItem> filteredRepeatedReplyItems = new ArrayList<>();
    List<String> unanchoredFilenames = new ArrayList<>();
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
      if (reply == null || hiddenByReplyFilter) {
        continue;
      }
      if (changeSetData.getDebugReviewMode()) {
        debugDetails.add(debugCodeBlocksReview.getDebugCodeBlock(replyItem, isHidden));
      }
      ReviewBatch batchMap = new ReviewBatch(reply);
      batchMap.setConcernId(replyItem.getConcernId());
      if (change.getIsCommentEvent() && replyItem.getId() != null) {
        setCommentBatchMap(batchMap, replyItem.getId(), filenameSanitizer, unanchoredFilenames);
      } else if (retargetToSurvivingLocation(replyItem, reviewReply, change, filenameSanitizer)
          || filenameSanitizer.sanitizeFilename(replyItem)) {
        setPatchSetReviewBatchMap(batchMap, replyItem);
      } else {
        // The file this finding was anchored to is not part of this revision - it was reverted, or
        // its content moved. Sending that path makes Gerrit reject the whole review, so the finding
        // is flattened to a patch-set-level comment instead of anchoring it: the batch keeps its
        // text and concern, and loses only the file, leaving the author the finding rather than
        // nothing. They are told which files could not be anchored.
        unanchoredFilenames.add(replyItem.getFilename());
      }
      batches.add(batchMap);
      log.debug("Added review batch from reply item: {}", batchMap);
    }
    if (!debugDetails.isEmpty()) {
      changeSetData.setReviewStatusMessage(String.join("\n\n", debugDetails));
    }
    setRepeatedCommentsMessage(filteredRepeatedReplyItems, change);
    setUnanchoredCommentsMessage(unanchoredFilenames);
    return batches;
  }

  /**
   * Tells the author which findings were left out because their file is not in this Change Set.
   *
   * <p>Set as a change-level message rather than as {@code reviewSystemMessage}, which suppresses
   * every comment: the review did happen, and only some of its findings could not be anchored.
   */
  private void setUnanchoredCommentsMessage(List<String> unanchoredFilenames) {
    if (unanchoredFilenames.isEmpty()) {
      return;
    }
    changeSetData.setReviewUnanchoredCommentsMessage(
        SystemMessageFormatter.getLocalizedMessage(
            localizer,
            "message.review.comments.not.anchored",
            String.join(", ", new TreeSet<>(unanchoredFilenames))));
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

  AiResponseContent getReviewReply(GerritChange change, String patchSet) throws Exception {
    return getReviewReply(change, patchSet, patchSet);
  }

  /**
   * Generates the review reply, refusing when the change is larger than the configured limit.
   *
   * <p>The size is the number of changed lines, not the number of lines of patch: see {@link
   * DiffStats#changedLines(String)} for why the difference matters.
   *
   * @param sizeReference the patch whose size decides whether the review runs. On a re-review the
   *     delta is measured instead, because that is what the review reasons about — the full patch
   *     of a change under review since an earlier patch set is mostly history by then. A topic
   *     review passes the largest of its changes, because the limit applies to a change rather than
   *     to a topic: an author can split a change, not a topic.
   */
  AiResponseContent getReviewReply(GerritChange change, String patchSet, String sizeReference)
      throws Exception {
    log.debug("Generating review reply for patch set.");
    String measured = changeSetData.getIncrementalPatchSet();
    if (measured == null || measured.isBlank()) {
      measured = sizeReference;
    }
    int changedLines = DiffStats.changedLines(measured);
    if (changedLines > config.getMaxReviewLines()) {
      log.warn(
          "Change too large for review, changed lines: {}, max allowed: {}",
          changedLines,
          config.getMaxReviewLines());
      changeSetData.setReviewSystemMessage(
          String.format(SPLIT_REVIEW_MSG, config.getMaxReviewLines()));
      return null;
    }

    boolean aiReviewConditionMet =
        !changeSetData.getForcedReview()
            || aiReviewApplicabilityChecker.isApplicable(change, config.getAiReviewApplicableIf());
    changeSetData.setAiReviewConditionMet(aiReviewConditionMet);
    changeSetData.setConditionLabels(
        aiReviewConditionMet
            ? gerritClient.getConditionLabels(change, config.getAiReviewApplicableIf())
            : Map.of());
    AiResponseContent response = openAiClient.ask(changeSetData, change, patchSet);
    changeSetData.getAiRequestCancellation().throwIfSupersessionRequested();
    return response;
  }

  Integer getReviewScore(GerritChange change, AiResponseContent reviewReply) {
    return reviewReply == null ? null : getReviewScore(change, reviewScores, reviewReply);
  }

  private Integer getReviewScore(
      GerritChange change, List<Double> scores, AiResponseContent reviewReply) {
    log.debug("Calculating review score for change ID: {}", change.getFullChangeId());
    if (changeSetData.getSuggestMode()) {
      return null;
    }
    if (config.isVotingEnabled()) {
      if (change.getIsCommentEvent()) {
        return null;
      }
      boolean allConcernsDismissed =
          reviewReply != null
              && reviewReply.getPendingConcernUpdates() != null
              && reviewReply
                  .getPendingConcernUpdates()
                  .get(change.getFullChangeId())
                  .map(ReviewConcernLedger::allConcernsDismissed)
                  .orElse(false);
      int reviewScore = scores.isEmpty() ? 0 : normalizeReviewScore(Collections.min(scores));
      if (reviewScore == 0
          && config.getConvertNeutralReviewScoreToPositive()
          && !allConcernsDismissed
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
    return Math.clamp(
        normalizedScore, permittedVotingRange.getMin(), permittedVotingRange.getMax());
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
