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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.function.Predicate;
import org.junit.Test;

public class ReviewConcernFixClosureTest {
  private static final String REASON = "the suggested fix was applied";
  private static final ConcernReviewerId REVIEWER =
      new ConcernReviewerId(ConcernReviewerId.Kind.SINGLE_AGENT, "agent");

  @Test
  public void closesAConcernWhoseFixHasLanded() {
    ReviewConcernLedger updated =
        close(ledger(concern("c1", ConcernStatus.PRESENT, fix())), appliedFix -> true);

    ReviewConcern closed = firstConcern(updated);
    assertEquals(ConcernStatus.FIXED, closed.getStatus());
    assertEquals(REASON, closed.getStatusReason());
  }

  @Test
  public void closesAnUncertainConcernToo() {
    // The reviewer was not sure, and the author applying its own suggested fix is the answer.
    assertEquals(
        ConcernStatus.FIXED,
        firstConcern(
                close(ledger(concern("c1", ConcernStatus.UNCERTAIN, fix())), appliedFix -> true))
            .getStatus());
  }

  @Test
  public void keepsAConcernWhoseFixHasNotLanded() {
    ReviewConcern kept =
        firstConcern(
            close(ledger(concern("c1", ConcernStatus.PRESENT, fix())), appliedFix -> false));

    assertEquals(ConcernStatus.PRESENT, kept.getStatus());
  }

  @Test
  public void neverClosesAConcernWithNoSuggestedFix() {
    ReviewConcern kept =
        firstConcern(close(ledger(concern("c1", ConcernStatus.PRESENT, null)), appliedFix -> true));

    assertEquals(
        "a concern nobody proposed a fix for is not closed by this",
        ConcernStatus.PRESENT,
        kept.getStatus());
  }

  @Test
  public void neverClosesAConcernWhoseFixHasNoCode() {
    SuggestedFix empty = fix();
    empty.setCode("   ");

    ReviewConcern kept =
        firstConcern(
            close(ledger(concern("c1", ConcernStatus.PRESENT, empty)), appliedFix -> true));

    assertEquals(ConcernStatus.PRESENT, kept.getStatus());
  }

  @Test
  public void doesNotOverrideAClosureSomeoneElseMade() {
    ReviewConcern concern = concern("c1", ConcernStatus.DISMISSED, fix());
    concern.setStatusReason("a user declared it non-actionable");

    ReviewConcern kept = firstConcern(close(ledger(concern), appliedFix -> true));

    assertEquals(ConcernStatus.DISMISSED, kept.getStatus());
    assertEquals("a user declared it non-actionable", kept.getStatusReason());
  }

  @Test
  public void isFixedRatherThanDismissedSoTheVoteStands() {
    // allConcernsDismissed suppresses the neutral-to-positive conversion. An accepted fix is the
    // opposite of a
    // concern being waved off, so routing it through DISMISSED would quietly deny the author their
    // vote.
    ReviewConcernLedger updated =
        close(ledger(concern("c1", ConcernStatus.PRESENT, fix())), appliedFix -> true);

    assertEquals(ConcernStatus.FIXED, firstConcern(updated).getStatus());
    assertFalse(updated.allConcernsDismissed());
  }

  @Test
  public void reportsTheFilesItClosed() {
    ReviewConcernFixClosure.Result result =
        ReviewConcernFixClosure.closeConcernsWithAppliedFixes(
            ledger(concern("c1", ConcernStatus.PRESENT, fix())), appliedFix -> true, REASON);

    assertEquals(List.of("A.kt"), result.closedFilenames());
  }

  @Test
  public void reportsNothingWhenItClosedNothing() {
    ReviewConcernFixClosure.Result result =
        ReviewConcernFixClosure.closeConcernsWithAppliedFixes(
            ledger(concern("c1", ConcernStatus.PRESENT, fix())), appliedFix -> false, REASON);

    assertTrue(result.closedFilenames().isEmpty());
  }

  @Test
  public void returnsTheSameLedgerWhenNothingChanged() {
    ReviewConcernLedger ledger = ledger(concern("c1", ConcernStatus.PRESENT, fix()));

    assertSame(ledger, close(ledger, appliedFix -> false));
  }

  @Test
  public void preservesTheLedgerAroundTheClosedConcern() {
    ReviewConcern second = concern("c2", ConcernStatus.PRESENT, fix());
    second.setId("c2");
    ReviewConcernLedger ledger = ledger(concern("c1", ConcernStatus.PRESENT, fix()), second);
    ledger.setLastReviewedCommit("abc123");

    // Only the first concern's fix is in the revision, so the second must survive untouched.
    ReviewConcernLedger updated = close(ledger, appliedFix -> true);

    assertEquals("abc123", updated.getLastReviewedCommit());
    assertEquals(1, updated.getReviewers().size());
    assertEquals(REVIEWER, updated.getReviewers().getFirst().getReviewer());
    assertEquals(2, updated.getReviewers().getFirst().getConcerns().size());
  }

  @Test
  public void nullLedgerStaysNull() {
    assertNull(
        ReviewConcernFixClosure.closeConcernsWithAppliedFixes(null, appliedFix -> true, REASON)
            .ledger());
  }

  private static ReviewConcernLedger close(
      ReviewConcernLedger ledger, Predicate<SuggestedFix> isApplied) {
    return ReviewConcernFixClosure.closeConcernsWithAppliedFixes(ledger, isApplied, REASON)
        .ledger();
  }

  private static ReviewConcern firstConcern(ReviewConcernLedger ledger) {
    return ledger.getReviewers().getFirst().getConcerns().getFirst();
  }

  private static ReviewConcernLedger ledger(ReviewConcern... concerns) {
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setReviewer(REVIEWER);
    reviewerConcerns.setConcerns(List.of(concerns));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(reviewerConcerns));
    return ledger;
  }

  private static ReviewConcern concern(String id, ConcernStatus status, SuggestedFix fix) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId(id);
    concern.setStatus(status);
    concern.setSuggestedFix(fix);
    return concern;
  }

  private static SuggestedFix fix() {
    SuggestedFix fix = new SuggestedFix();
    fix.setFilename("A.kt");
    fix.setCode("val applied = true");
    fix.setResolvedWhen("the key survives a transient failure");
    return fix;
  }
}
