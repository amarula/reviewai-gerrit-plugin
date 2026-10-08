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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review;

import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_PATCH_SET_FILENAME;
import static com.googlesource.gerrit.plugins.reviewai.utils.GerritUtils.isGerritNonFilePath;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Detaches concerns whose recorded files are no longer part of the change.
 *
 * <p>A concern outlives the patch set that raised it. The file it was about can be reverted, or its
 * content can move to a file the reviewer never saw, and the concern then points at code this
 * revision does not contain. This does not establish whether the defect still exists elsewhere.
 */
public final class ReviewConcernDetachment {
  private ReviewConcernDetachment() {}

  /**
   * Returns the ledger with every concern whose locations have all left the revision detached.
   *
   * <p>{@code fileInRevision} decides what "left" means, so this stays independent of Gerrit and
   * testable on its own. User dismissals are left untouched because their rationale remains valid
   * independently of the file's presence.
   *
   * @return the same ledger when nothing changed, so callers can compare by identity
   */
  public static ReviewConcernLedger detachConcernsWithoutFiles(
      ReviewConcernLedger ledger, Predicate<String> fileInRevision, String reason) {
    return detachConcernsWithoutFiles(ledger, ledger, fileInRevision, reason);
  }

  /**
   * Detaches historical concerns only. New findings about an unavailable file retain their status
   * and gain a patch-set location, matching their publication as change-level comments. Commit
   * message concerns remain active independently of the changed file list.
   */
  public static ReviewConcernLedger detachConcernsWithoutFiles(
      ReviewConcernLedger ledger,
      ReviewConcernLedger previousLedger,
      Predicate<String> fileInRevision,
      String reason) {
    if (ledger == null) {
      return null;
    }
    ledger.normalize();
    if (previousLedger != null) {
      previousLedger.normalize();
    }
    Set<String> previousConcernIds =
        previousLedger == null
            ? Set.of()
            : previousLedger.getReviewers().stream()
                .flatMap(reviewer -> reviewer.getConcerns().stream())
                .map(ReviewConcern::getId)
                .collect(Collectors.toSet());
    List<ReviewerConcerns> reviewers = new ArrayList<>();
    boolean changed = false;
    for (ReviewerConcerns reviewerConcerns : ledger.getReviewers()) {
      List<ReviewConcern> concerns = new ArrayList<>();
      for (ReviewConcern concern : reviewerConcerns.getConcerns()) {
        ReviewConcern updated =
            detachIfWithoutFile(
                concern, previousConcernIds.contains(concern.getId()), fileInRevision, reason);
        changed |= updated != concern;
        concerns.add(updated);
      }
      ReviewerConcerns updatedReviewer = new ReviewerConcerns();
      updatedReviewer.setReviewer(reviewerConcerns.getReviewer());
      updatedReviewer.setConcerns(concerns);
      reviewers.add(updatedReviewer);
    }
    if (!changed) {
      return ledger;
    }
    ReviewConcernLedger updatedLedger = new ReviewConcernLedger();
    updatedLedger.setSchemaVersion(ledger.getSchemaVersion());
    updatedLedger.setLastReviewedCommit(ledger.getLastReviewedCommit());
    updatedLedger.setReviewers(reviewers);
    return updatedLedger;
  }

  private static ReviewConcern detachIfWithoutFile(
      ReviewConcern concern, boolean historical, Predicate<String> fileInRevision, String reason) {
    if (concern.getStatus() == ConcernStatus.DISMISSED
        || concern.getStatus() == ConcernStatus.DETACHED) {
      return concern;
    }
    List<ConcernLocation> locations = concern.getLocations();
    if (locations.isEmpty()) {
      // No location is not the same as a location that is gone. Concerns about a commit message
      // carry none, and detaching those would close every one of them.
      return concern;
    }
    boolean anyInRevision =
        locations.stream()
            .map(ConcernLocation::getFilename)
            .anyMatch(
                filename ->
                    isGerritNonFilePath(filename) || fileInRevision.test(filename));
    if (anyInRevision) {
      return concern;
    }
    ReviewConcern updated = concern.copy();
    if (!historical) {
      ConcernLocation patchSetLocation = new ConcernLocation();
      patchSetLocation.setFilename(GERRIT_PATCH_SET_FILENAME);
      List<ConcernLocation> updatedLocations = new ArrayList<>(locations);
      updatedLocations.add(patchSetLocation);
      updated.setLocations(updatedLocations);
      return updated;
    }
    updated.setStatus(ConcernStatus.DETACHED);
    updated.setStatusReason(reason);
    return updated;
  }
}
