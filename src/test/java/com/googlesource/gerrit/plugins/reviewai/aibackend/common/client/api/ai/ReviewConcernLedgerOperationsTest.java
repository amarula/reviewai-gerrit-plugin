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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class ReviewConcernLedgerOperationsTest {
  @Test
  public void marksOnlyNonDismissedConcernsInDisabledScopeSkipped() {
    ReviewerConcerns commitMessageReviewer = new ReviewerConcerns();
    commitMessageReviewer.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "COMMIT_MESSAGE"));
    commitMessageReviewer.setConcerns(
        List.of(
            concern("present", ConcernStatus.PRESENT),
            concern("fixed", ConcernStatus.FIXED),
            concern("dismissed", ConcernStatus.DISMISSED)));
    ReviewerConcerns patchSetReviewer = new ReviewerConcerns();
    patchSetReviewer.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "PATCHSET"));
    patchSetReviewer.setConcerns(List.of(concern("patchset", ConcernStatus.PRESENT)));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(commitMessageReviewer, patchSetReviewer));
    ReviewFeedbackMemory feedback = new ReviewFeedbackMemory();
    feedback.setDisabledReviewScopes(Set.of(ReviewScope.COMMIT_MESSAGE));

    ReviewConcernLedger result =
        ledgerOperations().markDisabledConcernsSkipped(ledger, feedback);

    List<ReviewConcern> commitConcerns = result.getReviewers().getFirst().getConcerns();
    assertEquals(ConcernStatus.SKIPPED, commitConcerns.get(0).getStatus());
    assertEquals(
        "Commit-message review skipped because its scope is disabled.",
        commitConcerns.get(0).getStatusReason());
    assertEquals(ConcernStatus.SKIPPED, commitConcerns.get(1).getStatus());
    assertEquals(ConcernStatus.DISMISSED, commitConcerns.get(2).getStatus());
    assertEquals(ConcernStatus.PRESENT, result.getReviewers().get(1).getConcerns().getFirst().getStatus());
  }

  @Test
  public void marksConcernsOwnedByDisabledSpecializedAgentSkipped() {
    ReviewerConcerns testabilityReviewer = new ReviewerConcerns();
    testabilityReviewer.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SPECIALIZED_AGENT, "TESTABILITY"));
    testabilityReviewer.setConcerns(List.of(concern("testability", ConcernStatus.PRESENT)));
    ReviewerConcerns correctnessReviewer = new ReviewerConcerns();
    correctnessReviewer.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SPECIALIZED_AGENT, "CORRECTNESS"));
    correctnessReviewer.setConcerns(List.of(concern("correctness", ConcernStatus.PRESENT)));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(testabilityReviewer, correctnessReviewer));
    ReviewFeedbackMemory feedback = new ReviewFeedbackMemory();
    feedback.setDisabledSpecializedAgents(Set.of("TESTABILITY"));

    ReviewConcernLedger result =
        ledgerOperations().markDisabledConcernsSkipped(ledger, feedback);

    ReviewConcern testabilityConcern = result.getReviewers().getFirst().getConcerns().getFirst();
    assertEquals(ConcernStatus.SKIPPED, testabilityConcern.getStatus());
    assertEquals(
        "TESTABILITY review skipped because its specialized agent is disabled.",
        testabilityConcern.getStatusReason());
    assertEquals(
        ConcernStatus.PRESENT,
        result.getReviewers().get(1).getConcerns().getFirst().getStatus());
  }

  @Test
  public void reactivatedDismissedAndSkippedConcernsBecomeNewComments() {
    ConcernReviewerId reviewer =
        new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "PATCHSET");
    ReviewConcernLedger previousLedger = new ReviewConcernLedger();
    ReviewerConcerns previousConcerns = new ReviewerConcerns();
    previousConcerns.setReviewer(reviewer);
    previousConcerns.setConcerns(
        List.of(
            concern("dismissed", ConcernStatus.DISMISSED),
            concern("skipped", ConcernStatus.SKIPPED)));
    previousLedger.setReviewers(List.of(previousConcerns));
    ReviewConcernLedgerOperations operations = ledgerOperations();

    AiReplyItem dismissed =
        operations.toPresentReply(
            previousLedger, reviewer, concern("dismissed", ConcernStatus.PRESENT));
    AiReplyItem skipped =
        operations.toPresentReply(
            previousLedger, reviewer, concern("skipped", ConcernStatus.PRESENT));

    assertFalse(dismissed.isRepeated());
    assertTrue(
        dismissed.getReply().startsWith("Previously dismissed AI concern is actionable again:"));
    assertFalse(skipped.isRepeated());
    assertTrue(
        skipped
            .getReply()
            .startsWith("Previously skipped AI concern is actionable after review resumed:"));
  }

  private static ReviewConcern concern(String id, ConcernStatus status) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId(id);
    concern.setStatus(status);
    concern.setReply("Stored concern");
    concern.setDescription("Stored concern");
    return concern;
  }

  private static ReviewConcernLedgerOperations ledgerOperations() {
    Localizer localizer = mock(Localizer.class);
    when(localizer.getText("message.review.concern.specialized.agent.skipped"))
        .thenReturn("%s review skipped because its specialized agent is disabled.");
    return new ReviewConcernLedgerOperations(localizer);
  }
}
