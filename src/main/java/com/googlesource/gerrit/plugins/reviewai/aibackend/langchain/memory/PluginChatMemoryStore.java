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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class PluginChatMemoryStore implements ChatMemoryStore {
  private static final String DEFAULT_SCOPE = "default";

  private final ReviewAiDb db;

  @Inject
  public PluginChatMemoryStore(ReviewAiDb db) throws SQLException {
    this.db = db;
    initSchema();
  }

  public PluginChatMemoryStore(Path pluginDataDir) throws SQLException, IOException {
    this(new ReviewAiDb(pluginDataDir));
  }

  public PluginChatMemoryStore(String jdbcUrl) throws SQLException, IOException {
    this(new ReviewAiDb(Path.of("."), jdbcUrl));
  }

  private void initSchema() throws SQLException {
    db.initLangChainChatMemorySchema();
  }

  @Override
  public List<ChatMessage> getMessages(Object memoryId) {
    MemoryKey key = MemoryKey.from(memoryId);
    try {
      try (Connection c = db.getConnection()) {
        List<ChatMessage> result = new ArrayList<>();
        for (StoredMessage message : getMessageRecords(c, key)) {
          String json = message.messageJson();
          if (json != null && !json.isEmpty()) {
            result.add(ChatMessageDeserializer.messageFromJson(json));
          }
        }
        log.info(
            "Loaded {} chat messages from LangChain memory store for {}",
            result.size(),
            memoryId);
        return result;
      }
    } catch (Exception e) {
      log.warn("Failed to get chat memory messages for {}; returning empty list", memoryId, e);
      return new ArrayList<>();
    }
  }

  @Override
  public void updateMessages(Object memoryId, List<ChatMessage> messages) {
    MemoryKey key = MemoryKey.from(memoryId);
    try {
      if (messages == null || messages.isEmpty()) {
        deleteMessages(memoryId);
        return;
      }
      try (Connection c = db.getConnection();
          PreparedStatement ps = prepareInsertMessage(c)) {
        List<StoredMessage> existingRecords = getMessageRecords(c, key);
        List<String> existingMessages =
            existingRecords.stream().map(StoredMessage::messageJson).toList();
        List<String> updatedMessages =
            messages.stream().map(ChatMessageSerializer::messageToJson).toList();
        int overlapSize = getExistingOverlapSize(existingMessages, updatedMessages);
        deleteObsoleteMessages(c, existingRecords, existingMessages.size() - overlapSize);
        List<String> messagesToAppend =
            updatedMessages.subList(overlapSize, updatedMessages.size());
        for (String messageJson : messagesToAppend) {
          bindInsertMessage(ps, key, messageJson);
          ps.addBatch();
        }
        ps.executeBatch();
        log.info(
            "Persisted {} new chat messages into LangChain memory store for {}",
            messagesToAppend.size(),
            memoryId);
      }
    } catch (Exception e) {
      log.warn("Failed to persist chat memory messages for {}", memoryId, e);
    }
  }

  @Override
  public void deleteMessages(Object memoryId) {
    MemoryKey key = MemoryKey.from(memoryId);
    log.info("Clearing LangChain memory store for {}", memoryId);
    try {
      try (Connection c = db.getConnection();
          PreparedStatement ps =
              c.prepareStatement(
                  """
                  DELETE FROM langchain_chat_memory_messages
                  WHERE change_id = ? AND patch_set = ? AND scope = ?
                  """)) {
        bindMemoryKey(ps, key);
        ps.executeUpdate();
      }
    } catch (Exception e) {
      log.warn("Failed to clear chat memory messages for {}", memoryId, e);
    }
  }

  public void deleteMessagesForChangeSet(String changeId, int patchSet) {
    log.info(
        "Clearing LangChain memory store for change {} patch set {}", changeId, patchSet);
    try {
      try (Connection c = db.getConnection();
          PreparedStatement ps =
              c.prepareStatement(
                  """
                  DELETE FROM langchain_chat_memory_messages
                  WHERE change_id = ? AND patch_set = ?
                  """)) {
        ps.setString(1, changeId);
        ps.setInt(2, patchSet);
        ps.executeUpdate();
      }
    } catch (Exception e) {
      log.warn(
          "Failed to clear chat memory messages for change {} patch set {}",
          changeId,
          patchSet,
          e);
    }
  }

  private static void bindMemoryKey(PreparedStatement ps, MemoryKey key) throws SQLException {
    ps.setString(1, key.changeId());
    ps.setInt(2, key.patchSet());
    ps.setString(3, key.scope());
  }

  private static PreparedStatement prepareInsertMessage(Connection c) throws SQLException {
    return c.prepareStatement(
        """
        INSERT INTO langchain_chat_memory_messages
          (change_id, patch_set, scope, message_json, updated_at)
        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
        """);
  }

  private static void bindInsertMessage(PreparedStatement ps, MemoryKey key, String messageJson)
      throws SQLException {
    bindMemoryKey(ps, key);
    ps.setString(4, messageJson);
  }

  private List<StoredMessage> getMessageRecords(Connection c, MemoryKey key) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            SELECT id, message_json
            FROM langchain_chat_memory_messages
            WHERE change_id = ? AND patch_set = ?
            ORDER BY updated_at, id
            """)) {
      ps.setString(1, key.changeId());
      ps.setInt(2, key.patchSet());
      try (ResultSet rs = ps.executeQuery()) {
        List<StoredMessage> result = new ArrayList<>();
        while (rs.next()) {
          result.add(new StoredMessage(rs.getLong(1), rs.getString(2)));
        }
        return result;
      }
    }
  }

  private static void deleteObsoleteMessages(
      Connection c, List<StoredMessage> existingRecords, int obsoleteCount) throws SQLException {
    if (obsoleteCount <= 0) {
      return;
    }
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            DELETE FROM langchain_chat_memory_messages
            WHERE id = ?
            """)) {
      for (StoredMessage message : existingRecords.subList(0, obsoleteCount)) {
        ps.setLong(1, message.id());
        ps.addBatch();
      }
      ps.executeBatch();
    }
  }

  private static int getExistingOverlapSize(
      List<String> existingMessages, List<String> updatedMessages) {
    int maxOverlap = Math.min(existingMessages.size(), updatedMessages.size());
    for (int overlap = maxOverlap; overlap >= 0; overlap--) {
      if (matchesOverlap(existingMessages, updatedMessages, overlap)) {
        return overlap;
      }
    }
    return 0;
  }

  private static boolean matchesOverlap(
      List<String> existingMessages, List<String> updatedMessages, int overlap) {
    int existingStart = existingMessages.size() - overlap;
    for (int i = 0; i < overlap; i++) {
      if (!existingMessages.get(existingStart + i).equals(updatedMessages.get(i))) {
        return false;
      }
    }
    return true;
  }

  private record StoredMessage(long id, String messageJson) {}

  private record MemoryKey(String changeId, int patchSet, String scope) {
    private static MemoryKey from(Object memoryId) {
      if (memoryId instanceof LangChainMemoryId id) {
        return new MemoryKey(id.getChangeId(), id.getPatchSet(), id.getScope());
      }
      return new MemoryKey(String.valueOf(memoryId), 0, DEFAULT_SCOPE);
    }
  }
}
