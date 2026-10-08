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

package com.googlesource.gerrit.plugins.reviewai.review;

import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_PATCH_SET_FILENAME;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.comment.GerritCommentRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename.FilenameSanitizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/** Anchors review comments to surviving files or leaves them at patch-set level. */
@Slf4j
final class ReviewCommentAnchoring {
  private final GerritChange change;
  private final List<GerritComment> commentProperties;
  private final GerritCommentRange gerritCommentRange;
  private final FilenameSanitizer filenameSanitizer;

  ReviewCommentAnchoring(
      GerritClient gerritClient,
      GerritChange change,
      List<GerritComment> commentProperties,
      GerritCommentRange gerritCommentRange) {
    this.change = change;
    this.commentProperties = commentProperties;
    this.gerritCommentRange = gerritCommentRange;
    this.filenameSanitizer = new FilenameSanitizer(gerritClient, change);
  }

  void anchor(ReviewBatch batchMap, AiReplyItem replyItem, AiResponseContent reviewReply) {
    if (change.getIsCommentEvent() && replyItem.getId() != null) {
      setCommentBatchMap(batchMap, replyItem.getId());
    } else if (retargetToSurvivingLocation(replyItem, reviewReply)
        || filenameSanitizer.sanitizeFilename(replyItem)) {
      setPatchSetReviewBatchMap(batchMap, replyItem);
    }
    // An unavailable file anchor leaves the batch as a patch-set comment.
  }

  /**
   * Returns the review client, told which files this revision contains.
   *
   * <p>Every publication path goes through here. The list is always reset, so a failed lookup
   * cannot leave the previous change's files behind on a client that is reused across changes; a
   * lookup that fails yields an empty list, which the client reads as "unknown" and accepts
   * everything. A guard that cannot see the file list must not be the thing that breaks the review.
   */
  static GerritClientReview prepareClient(
      GerritClientReview clientReview, GerritClient gerritClient, GerritChange change) {
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
      AiReplyItem replyItem, AiResponseContent reviewReply) {
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
            .filter(location -> !GERRIT_PATCH_SET_FILENAME.equals(location.getFilename()))
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
   * Anchors a reply to a comment to the same file and line as the comment it answers.
   *
   * <p>The comment being answered is not necessarily on a file that still exists in this revision -
   * a patch set can drop it - and Gerrit rejects a comment posted against a file outside the
   * revision, failing the whole review. When the file is gone the reply keeps its text but loses
   * the anchor, becoming a patch-set-level comment instead.
   */
  private void setCommentBatchMap(ReviewBatch batchMap, Integer batchID) {
    if (commentProperties != null && batchID < commentProperties.size()) {
      GerritComment commentProperty = commentProperties.get(batchID);
      if (commentProperty != null) {
        if (!isPartOfCurrentPatchSet(filenameSanitizer, commentProperty.getFilename())) {
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
}
