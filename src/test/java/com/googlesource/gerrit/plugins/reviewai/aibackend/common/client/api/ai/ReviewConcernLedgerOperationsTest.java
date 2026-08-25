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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
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
        new ReviewConcernLedgerOperations().markDisabledScopeConcernsSkipped(ledger, feedback);

    List<ReviewConcern> commitConcerns = result.getReviewers().getFirst().getConcerns();
    assertEquals(ConcernStatus.SKIPPED, commitConcerns.get(0).getStatus());
    assertEquals(
        "Commit-message review skipped because its scope is disabled.",
        commitConcerns.get(0).getStatusReason());
    assertEquals(ConcernStatus.SKIPPED, commitConcerns.get(1).getStatus());
    assertEquals(ConcernStatus.DISMISSED, commitConcerns.get(2).getStatus());
    assertEquals(ConcernStatus.PRESENT, result.getReviewers().get(1).getConcerns().getFirst().getStatus());
  }

  private static ReviewConcern concern(String id, ConcernStatus status) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId(id);
    concern.setStatus(status);
    concern.setReply("Stored concern");
    concern.setDescription("Stored concern");
    return concern;
  }
}
