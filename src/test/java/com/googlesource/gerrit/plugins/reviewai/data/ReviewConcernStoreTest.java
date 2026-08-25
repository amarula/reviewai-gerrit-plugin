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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ReviewConcernStoreTest extends TestBase {
  private static final String REVIEWED_COMMIT = "a".repeat(40);

  private ReviewConcernStore store;

  @Before
  public void setUp() {
    store = new ReviewConcernStore(getTestReviewAiDb(), "change-1");
  }

  @Test
  public void missingLedgerIsDistinctFromAnEmptyLedger() {
    assertTrue(store.load().isEmpty());

    ReviewConcernLedger emptyLedger = new ReviewConcernLedger();
    store.save(emptyLedger);

    assertTrue(store.load().isPresent());
    assertTrue(store.load().orElseThrow().getReviewers().isEmpty());
  }

  @Test
  public void storesReviewerGroupsAndConcernsInDedicatedTables() throws Exception {
    ReviewConcernLedger ledger =
        ledger(
            reviewer(
                ConcernReviewerId.Kind.SCOPED_AGENT,
                "PATCHSET",
                concern("concern-1", ConcernStatus.PRESENT),
                concern("concern-2", ConcernStatus.FIXED),
                concern("concern-3", ConcernStatus.DISMISSED),
                concern("concern-4", ConcernStatus.SKIPPED)),
            reviewer(ConcernReviewerId.Kind.SCOPED_AGENT, "COMMIT_MESSAGE"));
    ledger.setLastReviewedCommit(REVIEWED_COMMIT);

    store.save(ledger);

    ReviewConcernLedger restored = store.load().orElseThrow();

    assertEquals(ledger, restored);
    assertEquals(1, rowCount("review_concern_ledgers"));
    assertEquals(2, rowCount("review_concern_reviewers"));
    assertEquals(4, rowCount("review_concerns"));
    assertEquals(1, concernCount(ConcernStatus.FIXED));
    assertEquals(1, concernCount(ConcernStatus.DISMISSED));
    assertEquals(1, concernCount(ConcernStatus.SKIPPED));
  }

  @Test
  public void saveReplacesExistingConcernRows() throws Exception {
    store.save(
        ledger(
            reviewer(
                ConcernReviewerId.Kind.SINGLE_AGENT,
                "PATCHSET",
                concern("old-1", ConcernStatus.PRESENT),
                concern("old-2", ConcernStatus.PRESENT))));
    ReviewConcernLedger replacement =
        ledger(
            reviewer(
                ConcernReviewerId.Kind.SINGLE_AGENT,
                "PATCHSET",
                concern("new-1", ConcernStatus.UNCERTAIN)));

    store.save(replacement);

    assertEquals(replacement, store.load().orElseThrow());
    assertEquals(1, rowCount("review_concerns"));
  }

  @Test
  public void failedReplacementKeepsExistingLedger() {
    ReviewConcernLedger existing =
        ledger(
            reviewer(
                ConcernReviewerId.Kind.SINGLE_AGENT,
                "PATCHSET",
                concern("existing-1", ConcernStatus.PRESENT)));
    store.save(existing);
    ReviewConcernLedger invalidReplacement =
        ledger(
            reviewer(
                ConcernReviewerId.Kind.SINGLE_AGENT,
                "x".repeat(256),
                concern("replacement-1", ConcernStatus.PRESENT)));

    try {
      store.save(invalidReplacement);
      fail("Expected the oversized reviewer name to be rejected by the database");
    } catch (RuntimeException expected) {
      // Expected.
    }

    assertEquals(existing, store.load().orElseThrow());
  }

  @Test
  public void ignoresUnsupportedStoredSchema() throws Exception {
    store.save(new ReviewConcernLedger());
    try (Connection connection = getTestReviewAiDb().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE review_concern_ledgers SET schema_version = ? WHERE change_id = ?")) {
      statement.setInt(1, ReviewConcernLedger.CURRENT_SCHEMA_VERSION + 1);
      statement.setString(2, "change-1");
      statement.executeUpdate();
    }

    assertTrue(store.load().isEmpty());
  }

  @Test
  public void rejectsUnsupportedSchemaAndMissingConcernId() {
    ReviewConcernLedger unsupported = new ReviewConcernLedger();
    unsupported.setSchemaVersion(ReviewConcernLedger.CURRENT_SCHEMA_VERSION + 1);
    assertRejected(() -> store.save(unsupported));

    assertRejected(
        () ->
            store.save(
                ledger(
                    reviewer(
                        ConcernReviewerId.Kind.SPECIALIZED_AGENT,
                        "CORRECTNESS",
                        new ReviewConcern()))));
  }

  @Test
  public void clearOnlyRemovesRequestedChange() {
    ReviewConcernStore otherStore = new ReviewConcernStore(getTestReviewAiDb(), "change-2");
    store.save(new ReviewConcernLedger());
    otherStore.save(new ReviewConcernLedger());

    store.clear();

    assertTrue(store.load().isEmpty());
    assertFalse(otherStore.load().isEmpty());
  }

  private int rowCount(String table) throws Exception {
    try (Connection connection = getTestReviewAiDb().getConnection();
        Statement statement = connection.createStatement();
        ResultSet results = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      results.next();
      return results.getInt(1);
    }
  }

  private int concernCount(ConcernStatus status) throws Exception {
    try (Connection connection = getTestReviewAiDb().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT COUNT(*) FROM review_concerns WHERE concern_status = ?")) {
      statement.setString(1, status.name());
      try (ResultSet results = statement.executeQuery()) {
        results.next();
        return results.getInt(1);
      }
    }
  }

  private static ReviewConcernLedger ledger(ReviewerConcerns... reviewers) {
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(reviewers));
    return ledger;
  }

  private static ReviewerConcerns reviewer(
      ConcernReviewerId.Kind kind, String name, ReviewConcern... concerns) {
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setReviewer(new ConcernReviewerId(kind, name));
    reviewerConcerns.setConcerns(List.of(concerns));
    return reviewerConcerns;
  }

  private static ReviewConcern concern(String id, ConcernStatus status) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId(id);
    concern.setStatus(status);
    concern.setDescription("Description for " + id);
    return concern;
  }

  private static void assertRejected(Runnable operation) {
    try {
      operation.run();
      fail("Expected invalid concern data to be rejected");
    } catch (IllegalArgumentException expected) {
      // Expected.
    }
  }
}
