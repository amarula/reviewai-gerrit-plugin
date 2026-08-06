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

package com.googlesource.gerrit.plugins.reviewai.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import org.junit.Before;
import org.junit.Test;

public class ReviewConcernPublisherTest extends TestBase {
  private ReviewConcernPublisher publisher;
  private GerritChange change;

  @Before
  public void setUp() {
    publisher = new ReviewConcernPublisher(getTestReviewAiDb());
    change =
        new GerritChange(
            "project", BranchNameKey.create("project", "main"), Change.key("I1234567890"));
  }

  @Test
  public void persistsUpdateForPublishedChange() {
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    PendingReviewConcernUpdates updates = new PendingReviewConcernUpdates();
    updates.put(change.getFullChangeId(), ledger);
    AiResponseContent response = new AiResponseContent("");
    response.setPendingConcernUpdates(updates);

    publisher.persist(response, change);

    ReviewConcernLedger restored =
        new ReviewConcernStore(getTestReviewAiDb(), change.getFullChangeId())
            .load()
            .orElseThrow();
    assertEquals(ledger, restored);
  }

  @Test
  public void loadsExistingLedgerForChange() {
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    new ReviewConcernStore(getTestReviewAiDb(), change.getFullChangeId()).save(ledger);

    assertEquals(ledger, publisher.load(change).orElseThrow());
  }

  @Test
  public void ignoresResponseWithoutAnUpdateForPublishedChange() {
    PendingReviewConcernUpdates updates = new PendingReviewConcernUpdates();
    updates.put("another-change", new ReviewConcernLedger());
    AiResponseContent response = new AiResponseContent("");
    response.setPendingConcernUpdates(updates);

    publisher.persist(response, change);

    assertTrue(
        new ReviewConcernStore(getTestReviewAiDb(), change.getFullChangeId()).load().isEmpty());
  }
}
