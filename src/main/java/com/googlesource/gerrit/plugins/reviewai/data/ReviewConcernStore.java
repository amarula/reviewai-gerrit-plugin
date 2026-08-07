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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

import com.google.gson.JsonParseException;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ReviewConcernStore {
  private final ReviewAiDb db;
  private final String changeId;

  public ReviewConcernStore(ReviewAiDb db, String changeId) {
    this.db = db;
    if (changeId == null || changeId.isBlank()) {
      throw new IllegalArgumentException("changeId must not be blank");
    }
    this.changeId = changeId;
    try {
      db.initReviewConcernSchema();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initialize review concern storage", e);
    }
  }

  public Optional<ReviewConcernLedger> load() {
    try (Connection connection = db.getConnection()) {
      LedgerMetadata metadata = loadLedgerMetadata(connection);
      if (metadata == null) {
        return Optional.empty();
      }
      if (metadata.schemaVersion() != ReviewConcernLedger.CURRENT_SCHEMA_VERSION) {
        log.warn(
            "Ignoring unsupported review concern ledger schema {} for change {}",
            metadata.schemaVersion(),
            changeId);
        return Optional.empty();
      }
      ReviewConcernLedger ledger = new ReviewConcernLedger();
      ledger.setSchemaVersion(metadata.schemaVersion());
      ledger.setLastReviewedCommit(metadata.lastReviewedCommit());
      ledger.setReviewers(loadReviewers(connection));
      ledger.normalize();
      return Optional.of(ledger);
    } catch (JsonParseException | IllegalArgumentException e) {
      log.warn("Ignoring invalid review concern data for change {}", changeId, e);
      return Optional.empty();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to load review concerns for change " + changeId, e);
    }
  }

  public void save(ReviewConcernLedger ledger) {
    validateLedger(ledger);
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try {
        upsertLedger(connection, ledger);
        deleteReviewerRows(connection);
        insertReviewerRows(connection, ledger.getReviewers());
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to save review concerns for change " + changeId, e);
    }
  }

  public void clear() {
    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "DELETE FROM review_concern_ledgers WHERE change_id = ?")) {
      statement.setString(1, changeId);
      statement.executeUpdate();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to clear review concerns for change " + changeId, e);
    }
  }

  private LedgerMetadata loadLedgerMetadata(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT schema_version, last_reviewed_commit
            FROM review_concern_ledgers
            WHERE change_id = ?
            """)) {
      statement.setString(1, changeId);
      try (ResultSet results = statement.executeQuery()) {
        return results.next()
            ? new LedgerMetadata(results.getInt(1), results.getString(2))
            : null;
      }
    }
  }

  private List<ReviewerConcerns> loadReviewers(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT reviewer_kind, reviewer_name
            FROM review_concern_reviewers
            WHERE change_id = ?
            ORDER BY reviewer_order
            """)) {
      statement.setString(1, changeId);
      try (ResultSet results = statement.executeQuery()) {
        List<ReviewerConcerns> reviewers = new ArrayList<>();
        while (results.next()) {
          ConcernReviewerId reviewer =
              new ConcernReviewerId(
                  ConcernReviewerId.Kind.valueOf(results.getString(1)), results.getString(2));
          ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
          reviewerConcerns.setReviewer(reviewer);
          reviewerConcerns.setConcerns(loadConcerns(connection, reviewer));
          reviewers.add(reviewerConcerns);
        }
        return reviewers;
      }
    }
  }

  private List<ReviewConcern> loadConcerns(Connection connection, ConcernReviewerId reviewer)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT concern_id, concern_status, concern_json
            FROM review_concerns
            WHERE change_id = ? AND reviewer_kind = ? AND reviewer_name = ?
            ORDER BY concern_order
            """)) {
      statement.setString(1, changeId);
      statement.setString(2, reviewer.getKind().name());
      statement.setString(3, reviewer.getName());
      try (ResultSet results = statement.executeQuery()) {
        List<ReviewConcern> concerns = new ArrayList<>();
        while (results.next()) {
          ReviewConcern concern = getGson().fromJson(results.getString(3), ReviewConcern.class);
          if (concern == null) {
            throw new JsonParseException("Stored review concern must not be null");
          }
          concern.setId(results.getString(1));
          concern.setStatus(ConcernStatus.valueOf(results.getString(2)));
          concern.normalize();
          concerns.add(concern);
        }
        return concerns;
      }
    }
  }

  private void upsertLedger(Connection connection, ReviewConcernLedger ledger)
      throws SQLException {
    String sql =
        db.getDialect()
            .upsert(
                "review_concern_ledgers",
                "change_id, schema_version, last_reviewed_commit, updated_at",
                "?, ?, ?, CURRENT_TIMESTAMP",
                "change_id",
                "schema_version = EXCLUDED.schema_version, "
                    + "last_reviewed_commit = EXCLUDED.last_reviewed_commit, "
                    + "updated_at = CURRENT_TIMESTAMP");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, changeId);
      statement.setInt(2, ledger.getSchemaVersion());
      statement.setString(3, ledger.getLastReviewedCommit());
      statement.executeUpdate();
    }
  }

  private void deleteReviewerRows(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "DELETE FROM review_concern_reviewers WHERE change_id = ?")) {
      statement.setString(1, changeId);
      statement.executeUpdate();
    }
  }

  private void insertReviewerRows(Connection connection, List<ReviewerConcerns> reviewers)
      throws SQLException {
    try (PreparedStatement reviewerStatement =
            connection.prepareStatement(
                """
                INSERT INTO review_concern_reviewers
                  (change_id, reviewer_kind, reviewer_name, reviewer_order, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """);
        PreparedStatement concernStatement =
            connection.prepareStatement(
                """
                INSERT INTO review_concerns
                  (change_id, reviewer_kind, reviewer_name, concern_id, concern_order,
                   concern_status, concern_json, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """)) {
      for (int reviewerOrder = 0; reviewerOrder < reviewers.size(); reviewerOrder++) {
        ReviewerConcerns reviewerConcerns = reviewers.get(reviewerOrder);
        ConcernReviewerId reviewer = reviewerConcerns.getReviewer();
        bindReviewer(reviewerStatement, reviewer, reviewerOrder);
        reviewerStatement.executeUpdate();
        for (int concernOrder = 0;
            concernOrder < reviewerConcerns.getConcerns().size();
            concernOrder++) {
          bindConcern(
              concernStatement,
              reviewer,
              reviewerConcerns.getConcerns().get(concernOrder),
              concernOrder);
          concernStatement.addBatch();
        }
      }
      concernStatement.executeBatch();
    }
  }

  private void bindReviewer(
      PreparedStatement statement, ConcernReviewerId reviewer, int reviewerOrder)
      throws SQLException {
    statement.setString(1, changeId);
    statement.setString(2, reviewer.getKind().name());
    statement.setString(3, reviewer.getName());
    statement.setInt(4, reviewerOrder);
  }

  private void bindConcern(
      PreparedStatement statement,
      ConcernReviewerId reviewer,
      ReviewConcern concern,
      int concernOrder)
      throws SQLException {
    statement.setString(1, changeId);
    statement.setString(2, reviewer.getKind().name());
    statement.setString(3, reviewer.getName());
    statement.setString(4, concern.getId());
    statement.setInt(5, concernOrder);
    statement.setString(6, concern.getStatus().name());
    statement.setString(7, getGson().toJson(concern));
  }

  private static void validateLedger(ReviewConcernLedger ledger) {
    if (ledger == null) {
      throw new IllegalArgumentException("Review concern ledger must not be null");
    }
    if (ledger.getReviewers() == null) {
      ledger.setReviewers(List.of());
    }
    if (ledger.getSchemaVersion() != ReviewConcernLedger.CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported review concern ledger schema");
    }
    if (ledger.getLastReviewedCommit() != null) {
      ledger.setLastReviewedCommit(ledger.getLastReviewedCommit().trim());
      if (ledger.getLastReviewedCommit().isEmpty()) {
        ledger.setLastReviewedCommit(null);
      } else if (ledger.getLastReviewedCommit().length() > 128) {
        throw new IllegalArgumentException("Review concern commit ID is too long");
      }
    }
    Set<String> reviewerKeys = new HashSet<>();
    for (ReviewerConcerns reviewerConcerns : ledger.getReviewers()) {
      if (reviewerConcerns == null || reviewerConcerns.getReviewer() == null) {
        throw new IllegalArgumentException("Review concern ledger contains a missing reviewer");
      }
      ConcernReviewerId reviewer = reviewerConcerns.getReviewer();
      if (reviewer.getKind() == null || reviewer.getName() == null || reviewer.getName().isBlank()) {
        throw new IllegalArgumentException("Review concern ledger contains an invalid reviewer");
      }
      String reviewerKey = reviewer.getKind().name() + '\u0000' + reviewer.getName();
      if (!reviewerKeys.add(reviewerKey)) {
        throw new IllegalArgumentException("Review concern ledger contains duplicate reviewers");
      }
      if (reviewerConcerns.getConcerns() == null) {
        reviewerConcerns.setConcerns(List.of());
      }
      Set<String> concernIds = new HashSet<>();
      for (ReviewConcern concern : reviewerConcerns.getConcerns()) {
        if (concern == null || concern.getId() == null || concern.getId().isBlank()) {
          throw new IllegalArgumentException("Stored review concerns require stable IDs");
        }
        concern.normalize();
        if (!concernIds.add(concern.getId())) {
          throw new IllegalArgumentException("Reviewer contains duplicate concern IDs");
        }
      }
    }
  }

  private static void rollback(Connection connection, Exception failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private record LedgerMetadata(int schemaVersion, String lastReviewedCommit) {}
}
