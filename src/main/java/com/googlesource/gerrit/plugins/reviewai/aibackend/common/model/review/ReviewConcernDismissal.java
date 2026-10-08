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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Closes concerns whose code is no longer part of the change.
 *
 * <p>A concern outlives the patch set that raised it. The file it was about can be reverted, or its
 * content can move to a file the reviewer never saw, and the concern then points at code this
 * revision does not contain. Nothing can be done about it here - there is no line to fix and no
 * place to retort - so keeping it open leaves the author with an unanswerable thread and the AI
 * re-litigating a file that is gone.
 */
public final class ReviewConcernDismissal {
  private ReviewConcernDismissal() {}

  /**
   * Returns the ledger with every concern whose locations have all left the revision dismissed.
   *
   * <p>{@code fileInRevision} decides what "left" means, so this stays independent of Gerrit and
   * testable on its own. Concerns that are already closed are left untouched, because their status
   * reason records what a user or the reviewer concluded and that is worth more than this one.
   *
   * @return the same ledger when nothing changed, so callers can compare by identity
   */
  public static ReviewConcernLedger dismissConcernsWithoutFiles(
      ReviewConcernLedger ledger, Predicate<String> fileInRevision, String reason) {
    if (ledger == null) {
      return null;
    }
    ledger.normalize();
    List<ReviewerConcerns> reviewers = new ArrayList<>();
    boolean changed = false;
    for (ReviewerConcerns reviewerConcerns : ledger.getReviewers()) {
      List<ReviewConcern> concerns = new ArrayList<>();
      for (ReviewConcern concern : reviewerConcerns.getConcerns()) {
        ReviewConcern updated = dismissIfWithoutFile(concern, fileInRevision, reason);
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

  private static ReviewConcern dismissIfWithoutFile(
      ReviewConcern concern, Predicate<String> fileInRevision, String reason) {
    if (concern.getStatus() != ConcernStatus.PRESENT
        && concern.getStatus() != ConcernStatus.UNCERTAIN) {
      return concern;
    }
    List<ConcernLocation> locations = concern.getLocations();
    if (locations.isEmpty()) {
      // No location is not the same as a location that is gone. Concerns about a commit message
      // carry none, and dismissing those would close every one of them.
      return concern;
    }
    boolean anyInRevision =
        locations.stream().map(ConcernLocation::getFilename).anyMatch(fileInRevision);
    if (anyInRevision) {
      return concern;
    }
    ReviewConcern dismissed = concern.copy();
    dismissed.setStatus(ConcernStatus.DISMISSED);
    dismissed.setStatusReason(reason);
    dismissed.setAutomaticDismissal(true);
    return dismissed;
  }
}
