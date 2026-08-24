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

import java.util.List;
import org.junit.Test;

public class PendingReviewConcernUpdatesTest {
  @Test
  public void mergesReviewerUpdatesForTheSameChange() {
    ConcernReviewerId patchset =
        new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "PATCHSET");
    ConcernReviewerId commitMessage =
        new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "COMMIT_MESSAGE");
    PendingReviewConcernUpdates updates = new PendingReviewConcernUpdates();
    updates.put("change", ledger(patchset));

    PendingReviewConcernUpdates additional = new PendingReviewConcernUpdates();
    additional.put("change", ledger(commitMessage));
    updates.mergeFrom(additional);

    assertEquals(2, updates.get("change").orElseThrow().getReviewers().size());
  }

  @Test
  public void newerUpdateReplacesTheSameReviewer() {
    ConcernReviewerId reviewer =
        new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "PATCHSET");
    PendingReviewConcernUpdates updates = new PendingReviewConcernUpdates();
    updates.put("change", ledger(reviewer));
    ReviewConcern concern = new ReviewConcern();
    concern.setId("new-concern");
    updates.put("change", ledger(reviewer, concern));

    List<ReviewConcern> concerns =
        updates.get("change").orElseThrow().getReviewers().getFirst().getConcerns();
    assertEquals(List.of(concern), concerns);
  }

  private ReviewConcernLedger ledger(
      ConcernReviewerId reviewer, ReviewConcern... concerns) {
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setReviewer(reviewer);
    reviewerConcerns.setConcerns(List.of(concerns));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(reviewerConcerns));
    return ledger;
  }
}
