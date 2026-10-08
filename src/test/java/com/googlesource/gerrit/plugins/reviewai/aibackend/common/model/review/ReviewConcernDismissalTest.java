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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class ReviewConcernDismissalTest {
  private static final String REASON = "the file is gone";
  private static final ConcernReviewerId REVIEWER =
      new ConcernReviewerId(ConcernReviewerId.Kind.SINGLE_AGENT, "agent");

  @Test
  public void dismissesAConcernWhoseOnlyFileHasLeft() {
    ReviewConcernLedger ledger = ledger(concern("c1", ConcernStatus.PRESENT, "Gone.java"));

    ReviewConcern dismissed =
        firstConcern(
            ReviewConcernDismissal.dismissConcernsWithoutFiles(
                ledger, "Present.java"::equals, REASON));

    assertEquals(ConcernStatus.DISMISSED, dismissed.getStatus());
    assertEquals(REASON, dismissed.getStatusReason());
    assertTrue(
        "an automatic dismissal must be recognisable, or it would earn the vote a user's "
            + "dismissal earns",
        Boolean.TRUE.equals(dismissed.getAutomaticDismissal()));
  }

  @Test
  public void dismissesAnUncertainConcernToo() {
    ReviewConcernLedger ledger = ledger(concern("c1", ConcernStatus.UNCERTAIN, "Gone.java"));

    assertEquals(
        ConcernStatus.DISMISSED,
        firstConcern(
                ReviewConcernDismissal.dismissConcernsWithoutFiles(
                    ledger, "Present.java"::equals, REASON))
            .getStatus());
  }

  @Test
  public void keepsAConcernThatStillHasOneLocationInTheRevision() {
    ReviewConcern concern = concern("c1", ConcernStatus.PRESENT, "Gone.java");
    concern.setLocations(List.of(location("Gone.java"), location("Present.java")));
    ReviewConcernLedger ledger = ledger(concern);

    ReviewConcern kept =
        firstConcern(
            ReviewConcernDismissal.dismissConcernsWithoutFiles(
                ledger, "Present.java"::equals, REASON));

    assertEquals(ConcernStatus.PRESENT, kept.getStatus());
    assertNull(kept.getAutomaticDismissal());
  }

  @Test
  public void leavesAConcernWithoutLocationsAlone() {
    // A commit-message concern carries no location, and "no locations" is not "every location is
    // gone" - reading it that way would close every one of them at once.
    ReviewConcern concern = concern("c1", ConcernStatus.PRESENT, null);
    concern.setLocations(List.of());

    ReviewConcern kept =
        firstConcern(
            ReviewConcernDismissal.dismissConcernsWithoutFiles(
                ledger(concern), filename -> false, REASON));

    assertEquals(ConcernStatus.PRESENT, kept.getStatus());
  }

  @Test
  public void leavesAnAlreadyClosedConcernAlone() {
    ReviewConcern concern = concern("c1", ConcernStatus.FIXED, "Gone.java");
    concern.setStatusReason("fixed by the author");

    ReviewConcern kept =
        firstConcern(
            ReviewConcernDismissal.dismissConcernsWithoutFiles(
                ledger(concern), filename -> false, REASON));

    assertEquals(ConcernStatus.FIXED, kept.getStatus());
    assertEquals(
        "the reviewer's own reason is worth more than ours",
        "fixed by the author",
        kept.getStatusReason());
  }

  @Test
  public void returnsTheSameLedgerWhenNothingChanged() {
    ReviewConcernLedger ledger = ledger(concern("c1", ConcernStatus.PRESENT, "Present.java"));

    assertSame(
        ledger,
        ReviewConcernDismissal.dismissConcernsWithoutFiles(ledger, "Present.java"::equals, REASON));
  }

  @Test
  public void preservesTheLedgerAroundTheDismissedConcern() {
    ReviewConcernLedger ledger =
        ledger(
            concern("c1", ConcernStatus.PRESENT, "Gone.java"),
            concern("c2", ConcernStatus.PRESENT, "Present.java"));
    ledger.setLastReviewedCommit("abc123");

    ReviewConcernLedger updated =
        ReviewConcernDismissal.dismissConcernsWithoutFiles(ledger, "Present.java"::equals, REASON);

    assertEquals("abc123", updated.getLastReviewedCommit());
    assertEquals(1, updated.getReviewers().size());
    assertEquals(REVIEWER, updated.getReviewers().get(0).getReviewer());
    assertEquals(
        ConcernStatus.PRESENT, updated.getReviewers().get(0).getConcerns().get(1).getStatus());
  }

  @Test
  public void nullLedgerStaysNull() {
    assertNull(ReviewConcernDismissal.dismissConcernsWithoutFiles(null, filename -> true, REASON));
  }

  @Test
  public void automaticDismissalCountsTowardsAllConcernsDismissed() {
    // The predicate suppresses the neutral-to-positive score conversion, and that suppression is
    // right exactly when no user judged the code. Excluding automatic dismissals here would let an
    // author earn a positive vote by deleting the file the concern was about.
    ReviewConcern userDismissed = concern("c1", ConcernStatus.PRESENT, "Gone.java");
    userDismissed.setStatus(ConcernStatus.DISMISSED);
    assertTrue(ledger(userDismissed).allConcernsDismissed());

    ReviewConcernLedger automatic =
        ReviewConcernDismissal.dismissConcernsWithoutFiles(
            ledger(concern("c1", ConcernStatus.PRESENT, "Gone.java")), filename -> false, REASON);

    assertTrue(automatic.allConcernsDismissed());
  }

  private static ReviewConcern firstConcern(ReviewConcernLedger ledger) {
    return ledger.getReviewers().get(0).getConcerns().get(0);
  }

  private static ReviewConcernLedger ledger(ReviewConcern... concerns) {
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setReviewer(REVIEWER);
    reviewerConcerns.setConcerns(List.of(concerns));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(reviewerConcerns));
    return ledger;
  }

  private static ReviewConcern concern(String id, ConcernStatus status, String filename) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId(id);
    concern.setStatus(status);
    concern.setLocations(filename == null ? List.of() : List.of(location(filename)));
    return concern;
  }

  private static ConcernLocation location(String filename) {
    ConcernLocation location = new ConcernLocation();
    location.setFilename(filename);
    location.setLineNumber(1);
    return location;
  }
}
