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
import static com.googlesource.gerrit.plugins.reviewai.utils.JdbcUtils.hasTable;
import static com.googlesource.gerrit.plugins.reviewai.utils.JdbcUtils.setLongOrNull;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getLong;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getOrCreateObject;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getString;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import com.googlesource.gerrit.plugins.reviewai.web.model.ReviewAgentConversationInfo;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class ReviewAgentConversationStore {
  static final long SHARED_USER_ID = 0L;

  private static final String KEY_REVIEW_AGENT_CONVERSATIONS = "reviewAgentConversations";
  private static final String PATCH_SET_EVENT_TRIGGER_MESSAGE =
      "Patch set commit event triggered this ReviewAI request.";
  private static final Type CONVERSATION_MAP_TYPE =
      new TypeToken<Map<String, ReviewAgentConversationInfo>>() {}.getType();

  private final ReviewAiDb db;

  @Inject
  public ReviewAgentConversationStore(ReviewAiDb db) throws SQLException {
    this.db = db;
    initSchema();
  }

  ReviewAgentConversationStore(String jdbcUrl, Path pluginDataDir) throws SQLException, IOException {
    this(new ReviewAiDb(pluginDataDir, jdbcUrl));
  }

  Map<String, ReviewAgentConversationInfo> getConversations(String changeId, long userId) {
    return getConversations(changeId, userId, true);
  }

  Map<String, ReviewAgentConversationInfo> getOwnedConversations(String changeId, long userId) {
    return getConversations(changeId, userId, false);
  }

  private Map<String, ReviewAgentConversationInfo> getConversations(
      String changeId, long userId, boolean includeShared) {
    migrateLegacyConversations(changeId);
    try (Connection c = db.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                """
                SELECT conversation_id, user_id, title, timestamp_millis
                FROM review_agent_conversations
                WHERE change_id = ?
                  AND (user_id = ? OR (? AND user_id = ?))
                ORDER BY user_id, updated_at
                """)) {
      migrateLegacyTurnContent(c, changeId);
      migrateAutomaticReviewTurnsToPlainHistory(c, changeId);
      ps.setString(1, changeId);
      ps.setLong(2, userId);
      ps.setBoolean(3, includeShared && userId != SHARED_USER_ID);
      ps.setLong(4, SHARED_USER_ID);
      try (ResultSet rs = ps.executeQuery()) {
        Map<String, ReviewAgentConversationInfo> conversations = new LinkedHashMap<>();
        while (rs.next()) {
          String conversationId = rs.getString(1);
          long conversationUserId = rs.getLong(2);
          ReviewAgentConversationInfo conversation =
              conversations.computeIfAbsent(
                  conversationId,
                  id -> {
                    ReviewAgentConversationInfo newConversation = new ReviewAgentConversationInfo();
                    newConversation.id = id;
                    return newConversation;
                  });
          if (conversationUserId == userId || conversation.title == null) {
            conversation.title = rs.getString(3);
          }
          Long timestampMillis = rs.getObject(4, Long.class);
          if (conversationUserId == userId || conversation.timestampMillis == null) {
            conversation.timestampMillis = timestampMillis;
          }
          conversation.turns.addAll(getTurns(c, changeId, conversation.id, conversationUserId));
          conversations.put(conversation.id, conversation);
        }
        return conversations;
      }
    } catch (SQLException e) {
      log.warn("Failed to read review-agent conversations for change {}", changeId, e);
      return new LinkedHashMap<>();
    }
  }

  public List<String> getAutomaticReviewResponseTexts(String changeId) {
    List<String> responseTexts = new ArrayList<>();
    for (ReviewAgentConversationInfo conversation :
        getConversations(changeId, SHARED_USER_ID).values()) {
      if (conversation.turns == null) {
        continue;
      }
      for (JsonObject turn : conversation.turns) {
        if (isAutomaticReviewTurn(turn)) {
          responseTexts.addAll(getResponseTexts(turn));
        }
      }
    }
    return responseTexts;
  }

  void upsertConversation(String changeId, long userId, ReviewAgentConversationInfo conversation) {
    try (Connection c = db.getConnection()) {
      upsertConversation(c, changeId, userId, conversation);
    } catch (SQLException e) {
      log.warn(
          "Failed to store review-agent conversation {} for change {}",
          conversation.id,
          changeId,
          e);
    }
  }

  public void appendTurn(
      String changeId, String conversationId, String title, JsonObject turn, Long timestampMillis) {
    appendTurn(changeId, SHARED_USER_ID, conversationId, title, turn, timestampMillis);
  }

  public void appendTurn(
      String changeId,
      long userId,
      String conversationId,
      String title,
      JsonObject turn,
      Long timestampMillis) {
    try (Connection c = db.getConnection()) {
      String canonicalConversationId = canonicalConversationId(conversationId);
      upsertConversationHeader(c, changeId, userId, canonicalConversationId, title, timestampMillis);
      insertTurn(
          c,
          changeId,
          userId,
          canonicalConversationId,
          getNextTurnIndex(c, changeId, userId, canonicalConversationId),
          turn);
    } catch (SQLException e) {
      log.warn(
          "Failed to append review-agent conversation turn for conversation {} on change {}",
          conversationId,
          changeId,
          e);
    }
  }

  public void replaceTurn(
      String changeId,
      long userId,
      String conversationId,
      String title,
      JsonObject turn,
      Long timestampMillis,
      int turnIndex) {
    try (Connection c = db.getConnection()) {
      String canonicalConversationId = canonicalConversationId(conversationId);
      upsertConversationHeader(c, changeId, userId, canonicalConversationId, title, timestampMillis);
      if (hasTurn(c, changeId, userId, canonicalConversationId, turnIndex)) {
        updateTurn(c, changeId, userId, canonicalConversationId, turnIndex, turn);
      } else {
        insertTurn(c, changeId, userId, canonicalConversationId, turnIndex, turn);
      }
    } catch (SQLException e) {
      log.warn(
          "Failed to replace review-agent conversation turn {} for conversation {} on change {}",
          turnIndex,
          conversationId,
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
        upsertConversation(c, changeId, SHARED_USER_ID, conversation);
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
      Connection c, String changeId, long userId, ReviewAgentConversationInfo conversation)
      throws SQLException {
    conversation.id = canonicalConversationId(conversation.id);
    upsertConversationHeader(
        c, changeId, userId, conversation.id, conversation.title, conversation.timestampMillis);
    for (int i = 0; i < conversation.turns.size(); i++) {
      upsertTurn(c, changeId, userId, conversation.id, i, conversation.turns.get(i));
    }
  }

  private void upsertConversationHeader(
      Connection c,
      String changeId,
      long userId,
      String conversationId,
      String title,
      Long timestampMillis)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            MERGE INTO review_agent_conversations
              (change_id, user_id, conversation_id, title, timestamp_millis, updated_at)
            KEY(change_id, user_id, conversation_id)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """)) {
      ps.setString(1, changeId);
      ps.setLong(2, userId);
      ps.setString(3, conversationId);
      ps.setString(4, title);
      setLongOrNull(ps, 5, timestampMillis);
      ps.executeUpdate();
    }
  }

  private int getNextTurnIndex(Connection c, String changeId, long userId, String conversationId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            SELECT COALESCE(MAX(turn_index) + 1, 0)
            FROM review_agent_conversation_turns
            WHERE change_id = ? AND user_id = ? AND conversation_id = ?
            """)) {
      ps.setString(1, changeId);
      ps.setLong(2, userId);
      ps.setString(3, conversationId);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  private void insertTurn(
      Connection c,
      String changeId,
      long userId,
      String conversationId,
      int turnIndex,
      JsonObject turn)
      throws SQLException {
    String turnMetadataJson = getGson().toJson(turn);
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            INSERT INTO review_agent_conversation_turns
              (change_id, user_id, conversation_id, turn_index, user_message_id,
               turn_metadata_json, timestamp_millis, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            """)) {
      ps.setString(1, changeId);
      ps.setLong(2, userId);
      ps.setString(3, conversationId);
      ps.setInt(4, turnIndex);
      setLongOrNull(ps, 5, null);
      ps.setString(6, turnMetadataJson);
      setLongOrNull(ps, 7, getTimestampMillis(turn));
      ps.executeUpdate();
    }
  }

  private void upsertTurn(
      Connection c,
      String changeId,
      long userId,
      String conversationId,
      int turnIndex,
      JsonObject turn)
      throws SQLException {
    if (hasTurn(c, changeId, userId, conversationId, turnIndex)) {
      updateTurn(c, changeId, userId, conversationId, turnIndex, turn);
    } else {
      insertTurn(c, changeId, userId, conversationId, turnIndex, turn);
    }
  }

  private boolean hasTurn(
      Connection c, String changeId, long userId, String conversationId, int turnIndex)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            SELECT 1
            FROM review_agent_conversation_turns
            WHERE change_id = ? AND user_id = ? AND conversation_id = ? AND turn_index = ?
            """)) {
      ps.setString(1, changeId);
      ps.setLong(2, userId);
      ps.setString(3, conversationId);
      ps.setInt(4, turnIndex);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  private java.util.List<JsonObject> getTurns(
      Connection c, String changeId, String conversationId, long userId)
      throws SQLException {
    String canonicalConversationId = canonicalConversationId(conversationId);
    try (PreparedStatement ps =
            c.prepareStatement(
            """
            SELECT turn_metadata_json
            FROM review_agent_conversation_turns
            WHERE change_id = ? AND user_id = ? AND conversation_id = ?
            ORDER BY turn_index
            """)) {
      ps.setString(1, changeId);
      ps.setLong(2, userId);
      ps.setString(3, canonicalConversationId);
      try (ResultSet rs = ps.executeQuery()) {
        java.util.List<JsonObject> turns = new java.util.ArrayList<>();
        while (rs.next()) {
          turns.add(getGson().fromJson(rs.getString(1), JsonObject.class));
        }
        return turns;
      }
    }
  }

  private void migrateLegacyTurnContent(Connection c, String changeId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            SELECT conversation_id, turn_index, user_message_id, turn_metadata_json
            FROM review_agent_conversation_turns
            WHERE change_id = ? AND user_message_id IS NOT NULL
            """)) {
      ps.setString(1, changeId);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JsonObject turn = getGson().fromJson(rs.getString(4), JsonObject.class);
          restoreLegacyUserQuestion(c, rs.getObject(3, Long.class), turn);
          updateTurnContent(c, changeId, SHARED_USER_ID, rs.getString(1), rs.getInt(2), turn);
        }
      }
    }
  }

  private void updateTurnContent(
      Connection c,
      String changeId,
      long userId,
      String conversationId,
      int turnIndex,
      JsonObject turn)
      throws SQLException {
    updateTurn(c, changeId, userId, conversationId, turnIndex, turn);
  }

  private void updateTurn(
      Connection c,
      String changeId,
      long userId,
      String conversationId,
      int turnIndex,
      JsonObject turn)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            UPDATE review_agent_conversation_turns
            SET turn_metadata_json = ?, user_message_id = NULL, timestamp_millis = ?
            WHERE change_id = ? AND user_id = ? AND conversation_id = ? AND turn_index = ?
            """)) {
      ps.setString(1, getGson().toJson(turn));
      setLongOrNull(ps, 2, getTimestampMillis(turn));
      ps.setString(3, changeId);
      ps.setLong(4, userId);
      ps.setString(5, conversationId);
      ps.setInt(6, turnIndex);
      ps.executeUpdate();
    }
  }

  private void migrateAutomaticReviewTurnsToPlainHistory(Connection c, String changeId)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            SELECT conversation_id, turn_index, turn_metadata_json
            FROM review_agent_conversation_turns
            WHERE change_id = ? AND turn_metadata_json LIKE ?
            """)) {
      ps.setString(1, changeId);
      ps.setString(2, "%" + PATCH_SET_EVENT_TRIGGER_MESSAGE + "%");
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          JsonObject turn = getGson().fromJson(rs.getString(3), JsonObject.class);
          if (removeAutomaticReviewClientData(turn)) {
            updateTurnContent(c, changeId, SHARED_USER_ID, rs.getString(1), rs.getInt(2), turn);
          }
        }
      }
    }
  }

  private boolean removeAutomaticReviewClientData(JsonObject turn) {
    if (turn == null || !turn.has("user_input") || !turn.get("user_input").isJsonObject()) {
      return false;
    }
    JsonObject userInput = turn.getAsJsonObject("user_input");
    if (!PATCH_SET_EVENT_TRIGGER_MESSAGE.equals(getString(userInput, "user_question"))
        || !userInput.has("client_data")) {
      return false;
    }
    userInput.remove("client_data");
    return true;
  }

  private boolean isAutomaticReviewTurn(JsonObject turn) {
    if (turn == null || !turn.has("user_input") || !turn.get("user_input").isJsonObject()) {
      return false;
    }
    JsonObject userInput = turn.getAsJsonObject("user_input");
    return PATCH_SET_EVENT_TRIGGER_MESSAGE.equals(getString(userInput, "user_question"));
  }

  private List<String> getResponseTexts(JsonObject turn) {
    List<String> responseTexts = new ArrayList<>();
    if (turn == null || !turn.has("response") || !turn.get("response").isJsonObject()) {
      return responseTexts;
    }
    JsonObject response = turn.getAsJsonObject("response");
    if (!response.has("response_parts") || !response.get("response_parts").isJsonArray()) {
      return responseTexts;
    }
    for (JsonElement responsePartElement : response.getAsJsonArray("response_parts")) {
      if (!responsePartElement.isJsonObject()) {
        continue;
      }
      String responseText = getString(responsePartElement.getAsJsonObject(), "text");
      if (responseText != null && !responseText.isBlank()) {
        responseTexts.add(responseText);
      }
    }
    return responseTexts;
  }

  private void restoreLegacyUserQuestion(Connection c, Long userMessageId, JsonObject turn)
      throws SQLException {
    if (userMessageId == null) {
      return;
    }
    JsonObject userInput = getOrCreateObject(turn, "user_input");
    userInput.addProperty("user_question", readLegacyMessageText(c, userMessageId));
  }

  private String readLegacyMessageText(Connection c, long messageId) throws SQLException {
    if (!hasTable(c, "LANGCHAIN_CHAT_MEMORY_MESSAGES")) {
      return "";
    }
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

  private static Long getTimestampMillis(JsonObject object) {
    return getLong(object, "timestamp_millis");
  }

  public static String canonicalConversationId(String conversationId) {
    String normalizedConversationId = conversationId.toLowerCase(Locale.ROOT);
    try {
      return UUID.fromString(normalizedConversationId).toString();
    } catch (IllegalArgumentException e) {
      return UUID.nameUUIDFromBytes(normalizedConversationId.getBytes(StandardCharsets.UTF_8))
          .toString();
    }
  }
}
