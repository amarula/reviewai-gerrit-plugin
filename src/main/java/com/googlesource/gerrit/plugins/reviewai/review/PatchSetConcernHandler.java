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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename.FilenameSanitizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernDetachment;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.util.Set;
import java.util.function.Predicate;

/** Applies file removal to concern lifecycle, persistence, and review notices. */
final class PatchSetConcernHandler {
  private final GerritClient gerritClient;
  private final ChangeSetData changeSetData;
  private final Localizer localizer;

  PatchSetConcernHandler(
      GerritClient gerritClient, ChangeSetData changeSetData, Localizer localizer) {
    this.gerritClient = gerritClient;
    this.changeSetData = changeSetData;
    this.localizer = localizer;
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
