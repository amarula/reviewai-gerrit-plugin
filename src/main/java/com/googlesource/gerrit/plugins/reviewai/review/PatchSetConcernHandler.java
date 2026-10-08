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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename.FilenameSanitizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernDetachment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;

/** Applies file removal to concern lifecycle, persistence, and review notices. */
final class PatchSetConcernHandler {
  private final GerritClient gerritClient;
  private final ChangeSetData changeSetData;
  private final ReviewConcernPublisher reviewConcernPublisher;
  private final Localizer localizer;

  PatchSetConcernHandler(
      GerritClient gerritClient,
      ChangeSetData changeSetData,
      ReviewConcernPublisher reviewConcernPublisher,
      Localizer localizer) {
    this.gerritClient = gerritClient;
    this.changeSetData = changeSetData;
    this.reviewConcernPublisher = reviewConcernPublisher;
    this.localizer = localizer;
  }

  void detachPreviousConcernsForEmptyPatchSet(GerritChange change) {
    ReviewConcernLedger previousLedger = changeSetData.getPreviousReviewConcernLedger();
    if (previousLedger == null) {
      return;
    }
    PendingReviewConcernUpdates updates = new PendingReviewConcernUpdates();
    updates.put(change.getFullChangeId(), previousLedger);
    AiResponseContent response = new AiResponseContent("");
    response.setPendingConcernUpdates(updates);
    detachConcernsWithoutFiles(
        response,
        change,
        filename ->
            GERRIT_PATCH_SET_FILENAME.equals(filename)
                || (filename != null && filename.endsWith("/COMMIT_MSG")));
    setDetachedConcernsMessage(response, change, null);
    if (updates.get(change.getFullChangeId()).orElseThrow() != previousLedger) {
      reviewConcernPublisher.persist(response, change);
    }
  }

  /**
   * Detaches historical concerns whose files have left the change, before scoring and publication.
   *
   * <p>New findings can describe the removal itself, so they retain their status and gain a
   * patch-set location when their original anchor is unavailable. The publisher stores this
   * location so the next review also treats them as active concerns about the change.
   */
  void detachConcernsWithoutFiles(AiResponseContent reviewReply, GerritChange change) {
    if (reviewReply.getPendingConcernUpdates() == null) {
      return;
    }
    FilenameSanitizer filenameSanitizer = new FilenameSanitizer(gerritClient, change);
    detachConcernsWithoutFiles(reviewReply, change, filenameSanitizer::isPartOfPatchSet);
  }

  void detachConcernsWithoutFiles(
      AiResponseContent reviewReply, GerritChange change, Predicate<String> fileInRevision) {
    PendingReviewConcernUpdates pendingUpdates = reviewReply.getPendingConcernUpdates();
    if (pendingUpdates == null) {
      return;
    }
    String reason = localizer.getText("message.review.concern.resolution.file.removed");
    pendingUpdates
        .get(change.getFullChangeId())
        .map(
            ledger ->
                ReviewConcernDetachment.detachConcernsWithoutFiles(
                    ledger, changeSetData.getPreviousReviewConcernLedger(), fileInRevision, reason))
        .ifPresent(ledger -> pendingUpdates.replace(change.getFullChangeId(), ledger));
  }

  /**
   * Lists files whose detached concerns are omitted from publication and scoring.
   *
   * <p>Set as a change-level message rather than as {@code reviewSystemMessage}, which suppresses
   * every comment: detached findings are skipped while current findings are still published.
   */
  void setDetachedConcernsMessage(
      AiResponseContent reviewReply, GerritChange change, String topicFilenamePrefix) {
    changeSetData.setReviewDetachedConcernsMessage(null);
    PendingReviewConcernUpdates updates = reviewReply.getPendingConcernUpdates();
    if (updates == null) {
      return;
    }
    Set<String> detachedFilenames = new TreeSet<>();
    updates.get(change.getFullChangeId()).stream()
        .flatMap(ledger -> ledger.getReviewers().stream())
        .flatMap(reviewer -> reviewer.getConcerns().stream())
        .filter(concern -> concern.getStatus() == ConcernStatus.DETACHED)
        .flatMap(concern -> concern.getLocations().stream())
        .map(ConcernLocation::getFilename)
        .filter(filename -> filename != null && !filename.isBlank())
        .map(
            filename ->
                topicFilenamePrefix != null && filename.startsWith(topicFilenamePrefix)
                    ? filename.substring(topicFilenamePrefix.length())
                    : filename)
        .forEach(detachedFilenames::add);
    if (!detachedFilenames.isEmpty()) {
      changeSetData.setReviewDetachedConcernsMessage(
          SystemMessageFormatter.getLocalizedMessage(
              localizer, "message.review.concerns.detached", String.join(", ", detachedFilenames)));
    }
  }

  static Set<String> detachedConcernIds(AiResponseContent response) {
    return response.getPendingConcernUpdates() == null
        ? Set.of()
        : response.getPendingConcernUpdates().detachedConcernIds();
  }

  static boolean isDetachedConcern(AiReplyItem replyItem, Set<String> detachedConcernIds) {
    String concernId = replyItem.getConcernId();
    return concernId != null && detachedConcernIds.contains(concernId);
  }
}
