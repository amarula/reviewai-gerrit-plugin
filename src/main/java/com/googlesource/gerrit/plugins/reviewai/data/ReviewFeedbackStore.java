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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ReviewFeedbackStore {
  private static final String PENDING = "PENDING";
  private static final String PROCESSING = "PROCESSING";
  private static final String PROCESSED = "PROCESSED";

  private final ReviewAiDb db;
  private final String changeId;
  private final ReviewFeedbackMemoryStore memoryStore;

  public ReviewFeedbackStore(ReviewAiDb db, String changeId) {
    this.db = db;
    if (changeId == null || changeId.isBlank()) {
      throw new IllegalArgumentException("changeId must not be blank");
    }
    this.changeId = changeId;
    this.memoryStore = new ReviewFeedbackMemoryStore(db, changeId);
  }

  public Optional<ReviewFeedbackMemory> loadMemory() {
    return memoryStore.load();
  }

  public List<FeedbackComment> listComments() {
    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT comment_id, processing_state
                FROM review_feedback_comments
                WHERE change_id = ?
                ORDER BY updated_at, comment_id
                """)) {
      statement.setString(1, changeId);
      try (ResultSet results = statement.executeQuery()) {
        List<FeedbackComment> comments = new ArrayList<>();
        while (results.next()) {
          comments.add(new FeedbackComment(results.getString(1), results.getString(2)));
        }
        return comments;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to list review feedback for change " + changeId, e);
    }
  }

  public void enqueue(Collection<String> commentIds) {
    Set<String> normalizedIds = normalizeCommentIds(commentIds);
    if (normalizedIds.isEmpty()) {
      return;
    }
    try (Connection connection = db.getConnection()) {
      for (String commentId : normalizedIds) {
        enqueue(connection, commentId);
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to enqueue review feedback for change " + changeId, e);
    }
  }

  public Claim claimPending() {
    String token = UUID.randomUUID().toString();
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement =
            connection.prepareStatement(
                """
                UPDATE review_feedback_comments
                SET processing_state = ?, processing_token = ?, updated_at = CURRENT_TIMESTAMP
                WHERE change_id = ? AND processing_state = ?
                """)) {
          statement.setString(1, PROCESSING);
          statement.setString(2, token);
          statement.setString(3, changeId);
          statement.setString(4, PENDING);
          statement.executeUpdate();
        }
        List<String> commentIds = loadClaimedCommentIds(connection, token);
        connection.commit();
        return new Claim(token, commentIds);
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to claim review feedback for change " + changeId, e);
    }
  }

  public void complete(Claim claim, ReviewFeedbackMemory memory) {
    validateClaim(claim);
    ReviewFeedbackMemoryStore.normalizeAndValidate(memory);
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try {
        memoryStore.save(connection, memory);
        updateClaim(connection, claim, PROCESSED, null);
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to complete review feedback for change " + changeId, e);
    }
  }

  public void release(Claim claim) {
    validateClaim(claim);
    try (Connection connection = db.getConnection()) {
      updateClaim(connection, claim, PENDING, null);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to release review feedback for change " + changeId, e);
    }
  }

  public void forget() {
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try {
        memoryStore.clear(connection);
        try (PreparedStatement statement =
            connection.prepareStatement(
                """
                UPDATE review_feedback_comments
                SET processing_state = ?, processing_token = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE change_id = ? AND processing_state <> ?
                """)) {
          statement.setString(1, PROCESSED);
          statement.setString(2, changeId);
          statement.setString(3, PROCESSED);
          statement.executeUpdate();
        }
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to forget review feedback for change " + changeId, e);
    }
  }

  private void enqueue(Connection connection, String commentId) throws SQLException {
    try (PreparedStatement existing =
        connection.prepareStatement(
            """
            SELECT processing_state
            FROM review_feedback_comments
            WHERE change_id = ? AND comment_id = ?
            """)) {
      existing.setString(1, changeId);
      existing.setString(2, commentId);
      try (ResultSet results = existing.executeQuery()) {
        if (results.next()) {
          return;
        }
      }
    }
    try (PreparedStatement insert =
        connection.prepareStatement(
            """
            INSERT INTO review_feedback_comments
                (change_id, comment_id, processing_state, processing_token, updated_at)
            VALUES (?, ?, ?, NULL, CURRENT_TIMESTAMP)
            """)) {
      insert.setString(1, changeId);
      insert.setString(2, commentId);
      insert.setString(3, PENDING);
      insert.executeUpdate();
    } catch (SQLException e) {
      if (e.getSQLState() == null || !e.getSQLState().startsWith("23")) {
        throw e;
      }
    }
  }

  private List<String> loadClaimedCommentIds(Connection connection, String token)
      throws SQLException {
    List<String> commentIds = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT comment_id
            FROM review_feedback_comments
            WHERE change_id = ? AND processing_token = ?
            ORDER BY updated_at, comment_id
            """)) {
      statement.setString(1, changeId);
      statement.setString(2, token);
      try (ResultSet results = statement.executeQuery()) {
        while (results.next()) {
          commentIds.add(results.getString(1));
        }
      }
    }
    return commentIds;
  }

  private void updateClaim(
      Connection connection, Claim claim, String processingState, String processingToken)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE review_feedback_comments
            SET processing_state = ?, processing_token = ?, updated_at = CURRENT_TIMESTAMP
            WHERE change_id = ? AND processing_state = ? AND processing_token = ?
            """)) {
      statement.setString(1, processingState);
      statement.setString(2, processingToken);
      statement.setString(3, changeId);
      statement.setString(4, PROCESSING);
      statement.setString(5, claim.token());
      int updatedRows = statement.executeUpdate();
      if (updatedRows != claim.commentIds().size()) {
        throw new IllegalStateException("Review feedback claim is no longer current");
      }
    }
  }

  private static Set<String> normalizeCommentIds(Collection<String> commentIds) {
    if (commentIds == null) {
      throw new IllegalArgumentException("commentIds must not be null");
    }
    Set<String> normalizedIds = new LinkedHashSet<>();
    for (String commentId : commentIds) {
      if (commentId == null || commentId.isBlank()) {
        throw new IllegalArgumentException("commentIds must not contain blank IDs");
      }
      normalizedIds.add(commentId.trim());
    }
    return normalizedIds;
  }

  private static void validateClaim(Claim claim) {
    if (claim == null || claim.token() == null || claim.token().isBlank()) {
      throw new IllegalArgumentException("claim must have a token");
    }
  }

  private static void rollback(Connection connection, Exception failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  public record Claim(String token, List<String> commentIds) {
    public Claim {
      commentIds = List.copyOf(commentIds);
    }

    public boolean isEmpty() {
      return commentIds.isEmpty();
    }
  }

  public record FeedbackComment(String commentId, String processingState) {}
}
