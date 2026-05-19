/*
 * Copyright (c) 2026. The Android Open Source Project
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

package com.googlesource.gerrit.plugins.reviewai.web;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import com.googlesource.gerrit.plugins.reviewai.web.model.ReviewAgentConversationInfo;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
class ReviewAgentConversationStore {
  private static final String KEY_REVIEW_AGENT_CONVERSATIONS = "reviewAgentConversations";
  private static final String REVIEW_AGENT_SCOPE = "review_agent_conversations";
  private static final Type CONVERSATION_MAP_TYPE =
      new TypeToken<Map<String, ReviewAgentConversationInfo>>() {}.getType();

  private final ReviewAiDb db;

  @Inject
  ReviewAgentConversationStore(ReviewAiDb db) throws SQLException {
    this.db = db;
    initSchema();
  }

  ReviewAgentConversationStore(String jdbcUrl, Path pluginDataDir) throws SQLException, IOException {
    this(new ReviewAiDb(pluginDataDir, jdbcUrl));
  }

  Map<String, ReviewAgentConversationInfo> getConversations(String changeId) {
    migrateLegacyConversations(changeId);
    try (Connection c = db.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                """
                SELECT conversation_id, title, timestamp_millis
                FROM review_agent_conversations
                WHERE change_id = ?
                ORDER BY updated_at
                """)) {
      ps.setString(1, changeId);
      try (ResultSet rs = ps.executeQuery()) {
        Map<String, ReviewAgentConversationInfo> conversations = new LinkedHashMap<>();
        while (rs.next()) {
          ReviewAgentConversationInfo conversation = new ReviewAgentConversationInfo();
          conversation.id = rs.getString(1);
          conversation.title = rs.getString(2);
          conversation.timestampMillis = rs.getObject(3, Long.class);
          conversation.turns.addAll(getTurns(c, changeId, conversation.id));
          conversations.put(conversation.id, conversation);
        }
        return conversations;
      }
    } catch (SQLException e) {
      log.warn("Failed to read review-agent conversations for change {}", changeId, e);
      return new LinkedHashMap<>();
    }
  }

  void upsertConversation(String changeId, int patchSet, ReviewAgentConversationInfo conversation) {
    try (Connection c = db.getConnection()) {
      upsertConversation(c, changeId, patchSet, conversation);
    } catch (SQLException e) {
      log.warn(
          "Failed to store review-agent conversation {} for change {}",
          conversation.id,
          changeId,
          e);
    }
  }

  private void initSchema() throws SQLException {
    db.initReviewAgentConversationSchema();
  }

  private void migrateLegacyConversations(String changeId) {
    try (Connection c = db.getConnection()) {
      if (hasAnyConversation(c, changeId)) {
        return;
      }
      Path legacyFile = getLegacyConversationFile(changeId);
      if (Files.notExists(legacyFile)) {
        return;
      }
      Properties properties = new Properties();
      try (var input = Files.newInputStream(legacyFile)) {
        properties.load(input);
      }
      String legacyJson = properties.getProperty(KEY_REVIEW_AGENT_CONVERSATIONS);
      if (legacyJson == null || legacyJson.isEmpty()) {
        return;
      }
      Map<String, ReviewAgentConversationInfo> conversations =
          getGson().fromJson(legacyJson, CONVERSATION_MAP_TYPE);
      if (conversations == null) {
        return;
      }
      for (ReviewAgentConversationInfo conversation : conversations.values()) {
        upsertConversation(c, changeId, 0, conversation);
      }
    } catch (Exception e) {
      log.warn("Failed to migrate review-agent conversations for change {}", changeId, e);
    }
  }

  private Path getLegacyConversationFile(String changeId) {
    Path fullChangeIdFile = db.getPluginDataDir().resolve(changeId + ".data");
    if (Files.exists(fullChangeIdFile)) {
      return fullChangeIdFile;
    }
    int lastSeparator = changeId.lastIndexOf('~');
    if (lastSeparator < 0 || lastSeparator == changeId.length() - 1) {
      return fullChangeIdFile;
    }
    return db.getPluginDataDir().resolve(changeId.substring(lastSeparator + 1) + ".data");
  }

  private boolean hasAnyConversation(Connection c, String changeId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            SELECT 1
            FROM review_agent_conversations
            WHERE change_id = ?
            LIMIT 1
            """)) {
      ps.setString(1, changeId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private void upsertConversation(
      Connection c, String changeId, int patchSet, ReviewAgentConversationInfo conversation)
      throws SQLException {
    conversation.id = canonicalConversationId(conversation.id);
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            MERGE INTO review_agent_conversations
            KEY(change_id, conversation_id)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            """)) {
      ps.setString(1, changeId);
      ps.setString(2, conversation.id);
      ps.setString(3, conversation.title);
      setLongOrNull(ps, 4, conversation.timestampMillis);
      ps.executeUpdate();
    }
    deleteConversationUserMessages(c, changeId, conversation.id);
    deleteConversationTurns(c, changeId, conversation.id);
    int persistedTurnIndex = 0;
    for (JsonObject turn : conversation.turns) {
      if (!shouldPersistTurn(turn)) {
        continue;
      }
      insertTurn(c, changeId, patchSet, conversation.id, persistedTurnIndex++, turn);
    }
  }

  private void insertTurn(
      Connection c,
      String changeId,
      int patchSet,
      String conversationId,
      int turnIndex,
      JsonObject turn)
      throws SQLException {
    TurnMessages turnMessages = extractTurnMessages(c, changeId, patchSet, turn);
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            INSERT INTO review_agent_conversation_turns
              (change_id, conversation_id, turn_index, user_message_id,
               turn_metadata_json, timestamp_millis, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """)) {
      ps.setString(1, changeId);
      ps.setString(2, conversationId);
      ps.setInt(3, turnIndex);
      setLongOrNull(ps, 4, turnMessages.userMessageId());
      ps.setString(5, getGson().toJson(turnMessages.turnMetadata()));
      setLongOrNull(ps, 6, getTimestampMillis(turn));
      ps.executeUpdate();
    }
  }

  private TurnMessages extractTurnMessages(
      Connection c, String changeId, int patchSet, JsonObject turn)
      throws SQLException {
    JsonObject metadata = turn.deepCopy();
    Long userMessageId = null;
    String userQuestion = getUserQuestion(metadata);
    boolean storeInLangChainMemory = shouldStoreInLangChainMemory(userQuestion);
    if (storeInLangChainMemory) {
      userMessageId = insertMessage(c, changeId, patchSet, UserMessage.from(userQuestion));
      metadata.getAsJsonObject("user_input").remove("user_question");
    }
    return new TurnMessages(metadata, userMessageId);
  }

  private long insertMessage(Connection c, String changeId, int patchSet, ChatMessage message)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            INSERT INTO langchain_chat_memory_messages
              (change_id, patch_set, scope, message_json, updated_at)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            """,
            Statement.RETURN_GENERATED_KEYS)) {
      ps.setString(1, changeId);
      ps.setInt(2, patchSet);
      ps.setString(3, REVIEW_AGENT_SCOPE);
      ps.setString(4, ChatMessageSerializer.messageToJson(message));
      ps.executeUpdate();
      try (ResultSet rs = ps.getGeneratedKeys()) {
        if (!rs.next()) {
          throw new SQLException("No generated key returned for review-agent message");
        }
        return rs.getLong(1);
      }
    }
  }

  private java.util.List<JsonObject> getTurns(Connection c, String changeId, String conversationId)
      throws SQLException {
    String canonicalConversationId = canonicalConversationId(conversationId);
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            SELECT turn_index, user_message_id, turn_metadata_json
            FROM review_agent_conversation_turns
            WHERE change_id = ? AND conversation_id = ?
            ORDER BY turn_index
            """)) {
      ps.setString(1, changeId);
      ps.setString(2, canonicalConversationId);
      try (ResultSet rs = ps.executeQuery()) {
        java.util.List<JsonObject> turns = new java.util.ArrayList<>();
        while (rs.next()) {
          int turnIndex = rs.getInt(1);
          JsonObject turn = getGson().fromJson(rs.getString(3), JsonObject.class);
          restoreUserQuestion(c, rs.getObject(2, Long.class), turn);
          sanitizeTurn(turn);
          if (!shouldPersistTurn(turn)) {
            continue;
          }
          turns.add(turn);
        }
        return turns;
      }
    }
  }

  private void restoreUserQuestion(Connection c, Long userMessageId, JsonObject turn)
      throws SQLException {
    if (userMessageId == null) {
      return;
    }
    JsonObject userInput = getOrCreateObject(turn, "user_input");
    userInput.addProperty("user_question", readMessageText(c, userMessageId));
  }

  private String readMessageText(Connection c, long messageId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            SELECT message_json
            FROM langchain_chat_memory_messages
            WHERE id = ?
            """)) {
      ps.setLong(1, messageId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return "";
        }
        Object message = ChatMessageDeserializer.messageFromJson(rs.getString(1));
        if (message instanceof UserMessage userMessage) {
          return userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
          return aiMessage.text();
        }
        return "";
      }
    }
  }

  private void deleteConversationUserMessages(Connection c, String changeId, String conversationId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            DELETE FROM langchain_chat_memory_messages
            WHERE id IN (
              SELECT user_message_id
              FROM review_agent_conversation_turns
              WHERE change_id = ? AND conversation_id = ? AND user_message_id IS NOT NULL
            )
            """)) {
      ps.setString(1, changeId);
      ps.setString(2, conversationId);
      ps.executeUpdate();
    }
  }

  private void deleteConversationTurns(Connection c, String changeId, String conversationId)
      throws SQLException {
    try (PreparedStatement turns =
            c.prepareStatement(
                """
                DELETE FROM review_agent_conversation_turns
                WHERE change_id = ? AND conversation_id = ?
                """)) {
      turns.setString(1, changeId);
      turns.setString(2, conversationId);
      turns.executeUpdate();
    }
  }

  private static JsonObject getOrCreateObject(JsonObject parent, String key) {
    if (parent.has(key) && parent.get(key).isJsonObject()) {
      return parent.getAsJsonObject(key);
    }
    JsonObject child = new JsonObject();
    parent.add(key, child);
    return child;
  }

  private static String getUserQuestion(JsonObject turn) {
    if (!turn.has("user_input") || !turn.get("user_input").isJsonObject()) {
      return null;
    }
    return getString(turn.getAsJsonObject("user_input"), "user_question");
  }

  private static boolean shouldPersistTurn(JsonObject turn) {
    if (turn == null) {
      return false;
    }
    String userQuestion = getUserQuestion(turn);
    if (userQuestion != null && !userQuestion.isBlank()) {
      return true;
    }
    return getString(turn, "message") != null && !getString(turn, "message").isBlank();
  }

  private static void sanitizeTurn(JsonObject turn) {
    if (turn == null || !turn.has("response") || !turn.get("response").isJsonObject()) {
      return;
    }
    JsonObject response = turn.getAsJsonObject("response");
    if (!response.has("response_parts") || !response.get("response_parts").isJsonArray()) {
      return;
    }
    JsonArray responseParts = response.getAsJsonArray("response_parts");
    responseParts.forEach(
        part -> {
          if (!part.isJsonObject()) {
            return;
          }
          JsonObject responsePart = part.getAsJsonObject();
          String text = getString(responsePart, "text");
          if (text != null) {
            responsePart.addProperty("text", removeDuplicateSystemMessagePrefix(text));
          }
        });
  }

  private static String removeDuplicateSystemMessagePrefix(String text) {
    String prefix = "SYSTEM MESSAGE:";
    String leadingWhitespace = text.substring(0, text.length() - text.stripLeading().length());
    String strippedText = text.stripLeading();
    if (!strippedText.startsWith(prefix)) {
      return text;
    }
    int firstLineEnd = strippedText.indexOf('\n');
    String firstLine = firstLineEnd < 0 ? strippedText : strippedText.substring(0, firstLineEnd);
    String remainder = firstLineEnd < 0 ? "" : strippedText.substring(firstLineEnd).stripLeading();
    if (!remainder.contains(firstLine)) {
      return text;
    }
    return leadingWhitespace + remainder;
  }

  private static boolean shouldStoreInLangChainMemory(String userQuestion) {
    if (userQuestion == null || userQuestion.isBlank()) {
      return false;
    }
    String normalizedQuestion = userQuestion.stripLeading();
    return !normalizedQuestion.equals("/forget_thread")
        && !normalizedQuestion.startsWith("/forget_thread ");
  }

  private static String getString(JsonObject object, String key) {
    if (!object.has(key) || object.get(key).isJsonNull()) {
      return null;
    }
    return object.get(key).getAsString();
  }

  private static Long getTimestampMillis(JsonObject turn) {
    if (!turn.has("timestamp_millis") || turn.get("timestamp_millis").isJsonNull()) {
      return null;
    }
    return turn.get("timestamp_millis").getAsLong();
  }

  private static void setLongOrNull(PreparedStatement ps, int index, Long value)
      throws SQLException {
    if (value == null) {
      ps.setNull(index, java.sql.Types.BIGINT);
    } else {
      ps.setLong(index, value);
    }
  }

  static String canonicalConversationId(String conversationId) {
    String normalizedConversationId = conversationId.toLowerCase(Locale.ROOT);
    try {
      return UUID.fromString(normalizedConversationId).toString();
    } catch (IllegalArgumentException e) {
      return UUID.nameUUIDFromBytes(normalizedConversationId.getBytes(StandardCharsets.UTF_8))
          .toString();
    }
  }

  private record TurnMessages(JsonObject turnMetadata, Long userMessageId) {}
}
