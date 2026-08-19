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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ReviewFeedbackMemoryStore {
  private final ReviewAiDb db;
  private final String changeId;

  public ReviewFeedbackMemoryStore(ReviewAiDb db, String changeId) {
    this.db = db;
    if (changeId == null || changeId.isBlank()) {
      throw new IllegalArgumentException("changeId must not be blank");
    }
    this.changeId = changeId;
    try {
      db.initReviewFeedbackSchema();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initialize review feedback memory", e);
    }
  }

  public Optional<ReviewFeedbackMemory> load() {
    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT schema_version, memory_json
                FROM review_feedback_memories
                WHERE change_id = ?
                """)) {
      statement.setString(1, changeId);
      try (ResultSet results = statement.executeQuery()) {
        if (!results.next()) {
          return Optional.empty();
        }
        int schemaVersion = results.getInt(1);
        if (schemaVersion != ReviewFeedbackMemory.CURRENT_SCHEMA_VERSION) {
          log.warn(
              "Ignoring unsupported review feedback memory schema {} for change {}",
              schemaVersion,
              changeId);
          return Optional.empty();
        }
        ReviewFeedbackMemory memory =
            getGson().fromJson(results.getString(2), ReviewFeedbackMemory.class);
        if (memory == null || memory.getSchemaVersion() != schemaVersion) {
          throw new JsonParseException("Stored review feedback memory has an invalid schema");
        }
        normalizeAndValidate(memory);
        return Optional.of(memory);
      }
    } catch (JsonParseException | IllegalArgumentException e) {
      log.warn("Ignoring invalid review feedback memory for change {}", changeId, e);
      return Optional.empty();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to load review feedback memory for change " + changeId, e);
    }
  }

  public void save(ReviewFeedbackMemory memory) {
    normalizeAndValidate(memory);
    try (Connection connection = db.getConnection()) {
      save(connection, memory);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to save review feedback memory for change " + changeId, e);
    }
  }

  void save(Connection connection, ReviewFeedbackMemory memory) throws SQLException {
    normalizeAndValidate(memory);
    String sql =
        db.getDialect()
            .upsert(
                "review_feedback_memories",
                "change_id, schema_version, memory_json, updated_at",
                "?, ?, ?, CURRENT_TIMESTAMP",
                "change_id",
                "schema_version = EXCLUDED.schema_version, "
                    + "memory_json = EXCLUDED.memory_json, "
                    + "updated_at = CURRENT_TIMESTAMP");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, changeId);
      statement.setInt(2, memory.getSchemaVersion());
      statement.setString(3, getGson().toJson(memory));
      statement.executeUpdate();
    }
  }

  public void clear() {
    try (Connection connection = db.getConnection()) {
      clear(connection);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to clear review feedback memory for change " + changeId, e);
    }
  }

  void clear(Connection connection) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "DELETE FROM review_feedback_memories WHERE change_id = ?")) {
      statement.setString(1, changeId);
      statement.executeUpdate();
    }
  }

  static void normalizeAndValidate(ReviewFeedbackMemory memory) {
    if (memory == null) {
      throw new IllegalArgumentException("Review feedback memory must not be null");
    }
    if (memory.getSchemaVersion() != ReviewFeedbackMemory.CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported review feedback memory schema");
    }
    if (memory.getGenericFeedback() != null) {
      String genericFeedback = memory.getGenericFeedback().trim();
      memory.setGenericFeedback(genericFeedback.isEmpty() ? null : genericFeedback);
    }
    if (memory.getConcernFeedback() == null) {
      memory.setConcernFeedback(Map.of());
    } else {
      Map<String, String> normalizedFeedback = new LinkedHashMap<>();
      for (Map.Entry<String, String> entry : memory.getConcernFeedback().entrySet()) {
        String concernId = entry.getKey();
        String summary = entry.getValue();
        if (concernId == null || concernId.isBlank() || summary == null || summary.isBlank()) {
          throw new IllegalArgumentException(
              "Concern feedback requires a concern ID and a summary");
        }
        concernId = concernId.trim();
        if (normalizedFeedback.putIfAbsent(concernId, summary.trim()) != null) {
          throw new IllegalArgumentException(
              "Review feedback memory contains duplicate concern IDs");
        }
      }
      memory.setConcernFeedback(normalizedFeedback);
    }
    if (memory.getDisabledReviewScopes() == null) {
      memory.setDisabledReviewScopes(Set.of());
    } else {
      EnumSet<ReviewScope> disabledReviewScopes = EnumSet.noneOf(ReviewScope.class);
      for (ReviewScope scope : memory.getDisabledReviewScopes()) {
        if (scope == null || scope == ReviewScope.FULL) {
          throw new IllegalArgumentException(
              "Review feedback memory contains an invalid disabled scope");
        }
        disabledReviewScopes.add(scope);
      }
      memory.setDisabledReviewScopes(disabledReviewScopes);
    }
    if (memory.getDisabledSpecializedAgents() == null) {
      memory.setDisabledSpecializedAgents(Set.of());
    } else {
      Set<String> disabledSpecializedAgents = new LinkedHashSet<>();
      for (String agent : memory.getDisabledSpecializedAgents()) {
        if (agent == null || agent.isBlank()) {
          throw new IllegalArgumentException(
              "Review feedback memory contains an invalid disabled specialized agent");
        }
        disabledSpecializedAgents.add(
            agent.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
      }
      memory.setDisabledSpecializedAgents(disabledSpecializedAgents);
    }
  }
}
