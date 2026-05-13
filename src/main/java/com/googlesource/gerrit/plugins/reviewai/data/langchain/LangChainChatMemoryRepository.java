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

package com.googlesource.gerrit.plugins.reviewai.data.langchain;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Singleton
public class LangChainChatMemoryRepository {
  private final ReviewAiDb db;

  @Inject
  public LangChainChatMemoryRepository(ReviewAiDb db) throws SQLException {
    this.db = db;
    db.initLangChainChatMemorySchema();
  }

  public List<String> getMessageJsons(String changeId, int patchSet, String scope)
      throws SQLException {
    try (Connection c = db.getConnection()) {
      return getMessageRecords(c, changeId, patchSet, scope).stream()
          .map(StoredMessage::messageJson)
          .toList();
    }
  }

  public int updateMessages(
      String changeId, int patchSet, String scope, List<String> updatedMessages)
      throws SQLException {
    if (updatedMessages == null || updatedMessages.isEmpty()) {
      deleteMessages(changeId, patchSet, scope);
      return 0;
    }
    try (Connection c = db.getConnection();
        PreparedStatement ps = prepareInsertMessage(c)) {
      List<StoredMessage> existingRecords = getMessageRecords(c, changeId, patchSet, scope);
      List<String> existingMessages =
          existingRecords.stream().map(StoredMessage::messageJson).toList();
      int overlapSize = getExistingOverlapSize(existingMessages, updatedMessages);
      deleteObsoleteMessages(c, existingRecords, existingMessages.size() - overlapSize);
      List<String> messagesToAppend =
          updatedMessages.subList(overlapSize, updatedMessages.size());
      for (String messageJson : messagesToAppend) {
        bindInsertMessage(ps, changeId, patchSet, scope, messageJson);
        ps.addBatch();
      }
      ps.executeBatch();
      return messagesToAppend.size();
    }
  }

  public void deleteMessages(String changeId, int patchSet, String scope) throws SQLException {
    try (Connection c = db.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                """
                DELETE FROM langchain_chat_memory_messages
                WHERE change_id = ? AND patch_set = ? AND scope = ?
                """)) {
      ps.setString(1, changeId);
      ps.setInt(2, patchSet);
      ps.setString(3, scope);
      ps.executeUpdate();
    }
  }

  public void deleteMessagesForChangeSet(String changeId, int patchSet) throws SQLException {
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
  }

  private static PreparedStatement prepareInsertMessage(Connection c) throws SQLException {
    return c.prepareStatement(
        """
        INSERT INTO langchain_chat_memory_messages
          (change_id, patch_set, scope, message_json, updated_at)
        VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
        """);
  }

  private static void bindInsertMessage(
      PreparedStatement ps, String changeId, int patchSet, String scope, String messageJson)
      throws SQLException {
    ps.setString(1, changeId);
    ps.setInt(2, patchSet);
    ps.setString(3, scope);
    ps.setString(4, messageJson);
  }

  private List<StoredMessage> getMessageRecords(
      Connection c, String changeId, int patchSet, String scope)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            """
            SELECT id, message_json
            FROM langchain_chat_memory_messages
            WHERE change_id = ? AND patch_set = ? AND scope = ?
            ORDER BY updated_at, id
            """)) {
      ps.setString(1, changeId);
      ps.setInt(2, patchSet);
      ps.setString(3, scope);
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
}
