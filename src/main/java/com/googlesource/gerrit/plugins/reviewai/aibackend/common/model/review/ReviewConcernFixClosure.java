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
 * Closes the concerns whose suggested fix the author has applied.
 *
 * <p>This is the end of the loop that a suggestion opens. An author who applies exactly what
 * ReviewAI proposed must not meet the same concern again: the reviewer's own standard can be
 * stricter the second time round, and when it is, the plugin has no way to tell that apart from the
 * author ignoring the advice - it renders the new objection with the old comment's link and the
 * words "still holds". Closing the concern here makes that outcome impossible rather than merely
 * unlikely.
 *
 * <p>Whatever is still wrong after the fix is a separate problem and belongs in a new concern, with
 * its own wording and its own criterion. This class does not judge that; it only refuses to keep
 * the old one open.
 *
 * <p>{@code FIXED} rather than {@code DISMISSED}: the code the concern was about was changed as
 * asked. Dismissal would also drag the concern into {@link
 * ReviewConcernLedger#allConcernsDismissed}, which suppresses a positive vote - the opposite of
 * what an accepted fix means.
 */
public final class ReviewConcernFixClosure {
  private ReviewConcernFixClosure() {}

  /**
   * What a closure pass did.
   *
   * @param ledger the ledger to carry on with; the same instance that was passed in when nothing
   *     was closed
   * @param closedFilenames the files of the concerns this pass closed, so the author can be told
   *     which ones
   */
  public record Result(ReviewConcernLedger ledger, List<String> closedFilenames) {}

  /**
   * Returns the ledger with every concern whose suggested fix has arrived closed as fixed.
   *
   * @param isApplied decides whether a recorded fix is now in the revision, so this stays
   *     independent of how the revision is read and testable on its own
   */
  public static Result closeConcernsWithAppliedFixes(
      ReviewConcernLedger ledger, Predicate<SuggestedFix> isApplied, String reason) {
    if (ledger == null) {
      return new Result(null, List.of());
    }
    ledger.normalize();
    List<ReviewerConcerns> reviewers = new ArrayList<>();
    List<String> closedFilenames = new ArrayList<>();
    boolean changed = false;
    for (ReviewerConcerns reviewerConcerns : ledger.getReviewers()) {
      List<ReviewConcern> concerns = new ArrayList<>();
      for (ReviewConcern concern : reviewerConcerns.getConcerns()) {
        ReviewConcern updated = closeIfApplied(concern, isApplied, reason);
        if (updated != concern) {
          changed = true;
          addFilename(closedFilenames, concern);
        }
        concerns.add(updated);
      }
      ReviewerConcerns updatedReviewer = new ReviewerConcerns();
      updatedReviewer.setReviewer(reviewerConcerns.getReviewer());
      updatedReviewer.setConcerns(concerns);
      reviewers.add(updatedReviewer);
    }
    if (!changed) {
      return new Result(ledger, List.of());
    }
    ReviewConcernLedger updatedLedger = new ReviewConcernLedger();
    updatedLedger.setSchemaVersion(ledger.getSchemaVersion());
    updatedLedger.setLastReviewedCommit(ledger.getLastReviewedCommit());
    updatedLedger.setReviewers(reviewers);
    return new Result(updatedLedger, List.copyOf(closedFilenames));
  }

  private static ReviewConcern closeIfApplied(
      ReviewConcern concern, Predicate<SuggestedFix> isApplied, String reason) {
    if (concern.getStatus() != ConcernStatus.PRESENT
        && concern.getStatus() != ConcernStatus.UNCERTAIN) {
      // Already closed by something with more to say about it than "the fix landed".
      return concern;
    }
    SuggestedFix fix = concern.getSuggestedFix();
    if (fix == null || fix.getCode() == null || fix.getCode().isBlank()) {
      return concern;
    }
    if (!isApplied.test(fix)) {
      return concern;
    }
    ReviewConcern closed = concern.copy();
    closed.setStatus(ConcernStatus.FIXED);
    closed.setStatusReason(reason);
    return closed;
  }

  private static void addFilename(List<String> filenames, ReviewConcern concern) {
    SuggestedFix fix = concern.getSuggestedFix();
    String filename = fix == null ? null : fix.getFilename();
    if (filename != null && !filename.isBlank() && !filenames.contains(filename)) {
      filenames.add(filename);
    }
  }
}
