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
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getOrCreateObject;

import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.UserMessage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Stores and removes the operational review state belonging to a Gerrit Change. */
@Singleton
public final class ReviewChangeStateStore {
  private final ReviewAiDb db;

  @Inject
  public ReviewChangeStateStore(ReviewAiDb db) {
    this.db = db;
    try {
      db.initLangChainChatMemorySchema();
      db.initReviewConcernSchema();
      db.initReviewFeedbackSchema();
      db.initReviewAgentConversationSchema();
    } catch (SQLException e) {
      throw new RuntimeException("Failed to initialize review change state", e);
    }
  }

  /** Lists every Change that has operational review state. */
  public List<String> listChangeIds() {
    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT change_id FROM review_concern_ledgers
                UNION SELECT change_id FROM review_feedback_memories
                UNION SELECT change_id FROM review_feedback_comments
                UNION SELECT change_id FROM langchain_chat_memory_messages
                """);
        ResultSet results = statement.executeQuery()) {
      Set<String> changeIds = new TreeSet<>();
      while (results.next()) {
        changeIds.add(results.getString(1));
      }
      return List.copyOf(changeIds);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to list review change state", e);
    }
  }

  /** Removes all operational review state for a Change in one transaction. */
  public void clear(String changeId) {
    if (changeId == null || changeId.isBlank()) {
      throw new IllegalArgumentException("changeId must not be blank");
    }
    try (Connection connection = db.getConnection()) {
      connection.setAutoCommit(false);
      try {
        materializeLegacyConversationMessages(connection, changeId);
        deleteByChange(connection, "review_feedback_comments", changeId);
        deleteByChange(connection, "review_feedback_memories", changeId);
        deleteByChange(connection, "review_concern_ledgers", changeId);
        deleteByChange(connection, "langchain_chat_memory_messages", changeId);
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        rollback(connection, e);
        throw e;
      }
    } catch (SQLException e) {
      throw new RuntimeException("Failed to clear review state for change " + changeId, e);
    }
  }

  private static void materializeLegacyConversationMessages(
      Connection connection, String changeId) throws SQLException {
    List<LegacyConversationTurn> turns = new ArrayList<>();
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            SELECT t.user_id, t.conversation_id, t.turn_index, t.user_message_id,
                   t.turn_metadata_json, m.message_json
            FROM review_agent_conversation_turns t
            LEFT JOIN langchain_chat_memory_messages m ON m.id = t.user_message_id
            WHERE t.change_id = ? AND t.user_message_id IS NOT NULL
            """)) {
      statement.setString(1, changeId);
      try (ResultSet results = statement.executeQuery()) {
        while (results.next()) {
          turns.add(
              new LegacyConversationTurn(
                  results.getLong(1),
                  results.getString(2),
                  results.getInt(3),
                  results.getLong(4),
                  results.getString(5),
                  results.getString(6)));
        }
      }
    }

    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            UPDATE review_agent_conversation_turns
            SET turn_metadata_json = ?, user_message_id = NULL
            WHERE change_id = ? AND user_id = ? AND conversation_id = ?
              AND turn_index = ? AND user_message_id = ?
            """)) {
      for (LegacyConversationTurn turn : turns) {
        JsonObject metadata = getGson().fromJson(turn.metadataJson(), JsonObject.class);
        getOrCreateObject(metadata, "user_input")
            .addProperty("user_question", messageText(turn.messageJson()));
        statement.setString(1, getGson().toJson(metadata));
        statement.setString(2, changeId);
        statement.setLong(3, turn.userId());
        statement.setString(4, turn.conversationId());
        statement.setInt(5, turn.turnIndex());
        statement.setLong(6, turn.userMessageId());
        statement.addBatch();
      }
      statement.executeBatch();
    }
  }

  private static String messageText(String messageJson) {
    if (messageJson == null || messageJson.isBlank()) {
      return "";
    }
    Object message = ChatMessageDeserializer.messageFromJson(messageJson);
    if (message instanceof UserMessage userMessage) {
      return userMessage.singleText();
    }
    if (message instanceof AiMessage aiMessage) {
      return aiMessage.text();
    }
    return "";
  }

  private static void deleteByChange(
      Connection connection, String tableName, String changeId) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("DELETE FROM " + tableName + " WHERE change_id = ?")) {
      statement.setString(1, changeId);
      statement.executeUpdate();
    }
  }

  private static void rollback(Connection connection, Exception failure) {
    try {
      connection.rollback();
    } catch (SQLException rollbackFailure) {
      failure.addSuppressed(rollbackFailure);
    }
  }

  private record LegacyConversationTurn(
      long userId,
      String conversationId,
      int turnIndex,
      long userMessageId,
      String metadataJson,
      String messageJson) {}
}
