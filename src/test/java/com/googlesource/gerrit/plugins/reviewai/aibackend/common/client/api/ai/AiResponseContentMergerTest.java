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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class AiResponseContentMergerTest {
  @Test
  public void mergesPendingConcernUpdatesWithoutSerializingThem() {
    AiResponseContent patchsetResponse = response("PATCHSET");
    AiResponseContent commitResponse = response("COMMIT_MESSAGE");

    AiResponseContent merged =
        AiResponseContentMerger.merge(
            new ArrayList<>(List.of(patchsetResponse, commitResponse)));

    assertEquals(
        2,
        merged
            .getPendingConcernUpdates()
            .get("change")
            .orElseThrow()
            .getReviewers()
            .size());
    assertFalse(getGson().toJson(merged).contains("pendingConcernUpdates"));
  }

  private AiResponseContent response(String reviewerName) {
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, reviewerName));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(reviewerConcerns));
    PendingReviewConcernUpdates updates = new PendingReviewConcernUpdates();
    updates.put("change", ledger);
    AiResponseContent response = new AiResponseContent("");
    response.setReplies(new ArrayList<AiReplyItem>());
    response.setPendingConcernUpdates(updates);
    return response;
  }
}
