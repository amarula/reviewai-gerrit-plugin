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
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.LangChainMemoryId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import dev.langchain4j.data.message.UserMessage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ReviewChangeStateStoreTest extends TestBase {
  private static final String USER_MESSAGE_RESOURCE = "__files/langchain/chatMemoryUserMessage.txt";
  private static final String CHANGE_ID = "project~main~Iclosed";

  private ReviewAiDb db;
  private ReviewChangeStateStore stateStore;
  private PluginChatMemoryStore chatMemoryStore;

  @Before
  public void setUp() throws Exception {
    db = getTestReviewAiDb();
    stateStore = new ReviewChangeStateStore(db);
    chatMemoryStore = new PluginChatMemoryStore(db);
  }

  @Test
  public void clearsConcernFeedbackAndAllLangChainStateForChange() throws Exception {
    new ReviewConcernStore(db, CHANGE_ID).save(new ReviewConcernLedger());
    ReviewFeedbackStore feedbackStore = new ReviewFeedbackStore(db, CHANGE_ID);
    feedbackStore.enqueue(List.of("comment-1"));
    feedbackStore.complete(feedbackStore.claimPending(), new ReviewFeedbackMemory());
    feedbackStore.enqueue(List.of("comment-2"));
    String userMessage = readResource(USER_MESSAGE_RESOURCE);
    LangChainMemoryId firstPatchSet = new LangChainMemoryId(CHANGE_ID, 1, "review_code");
    LangChainMemoryId secondPatchSet = new LangChainMemoryId(CHANGE_ID, 2, "requests");
    chatMemoryStore.updateMessages(firstPatchSet, List.of(UserMessage.from(userMessage)));
    chatMemoryStore.updateMessages(secondPatchSet, List.of(UserMessage.from(userMessage)));
    new ReviewConcernStore(db, "project~main~Iopen").save(new ReviewConcernLedger());

    stateStore.clear(CHANGE_ID);

    assertTrue(new ReviewConcernStore(db, CHANGE_ID).load().isEmpty());
    assertTrue(feedbackStore.loadMemory().isEmpty());
    assertTrue(feedbackStore.listComments().isEmpty());
    assertTrue(chatMemoryStore.getMessages(firstPatchSet).isEmpty());
    assertTrue(chatMemoryStore.getMessages(secondPatchSet).isEmpty());
    assertEquals(List.of("project~main~Iopen"), stateStore.listChangeIds());
  }

  @Test
  public void materializesLegacyConversationMessageBeforeDeletingLangChainState() throws Exception {
    String userMessage = readResource(USER_MESSAGE_RESOURCE);
    LangChainMemoryId memoryId = new LangChainMemoryId(CHANGE_ID, 1, "requests");
    chatMemoryStore.updateMessages(memoryId, List.of(UserMessage.from(userMessage)));
    long messageId = storedMessageId();
    insertLegacyConversationTurn(messageId);

    stateStore.clear(CHANGE_ID);

    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                """
                SELECT user_message_id, turn_metadata_json
                FROM review_agent_conversation_turns
                WHERE change_id = ?
                """)) {
      statement.setString(1, CHANGE_ID);
      try (ResultSet results = statement.executeQuery()) {
        assertTrue(results.next());
        assertNull(results.getObject(1));
        JsonObject metadata = getGson().fromJson(results.getString(2), JsonObject.class);
        assertEquals(
            userMessage, metadata.getAsJsonObject("user_input").get("user_question").getAsString());
        assertFalse(results.next());
      }
    }
    assertTrue(chatMemoryStore.getMessages(memoryId).isEmpty());
  }

  private long storedMessageId() throws Exception {
    try (Connection connection = db.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "SELECT id FROM langchain_chat_memory_messages WHERE change_id = ?")) {
      statement.setString(1, CHANGE_ID);
      try (ResultSet results = statement.executeQuery()) {
        assertTrue(results.next());
        return results.getLong(1);
      }
    }
  }

  private void insertLegacyConversationTurn(long messageId) throws Exception {
    try (Connection connection = db.getConnection();
        PreparedStatement conversation =
            connection.prepareStatement(
                """
                INSERT INTO review_agent_conversations
                  (change_id, user_id, conversation_id, title)
                VALUES (?, 0, 'legacy-conversation', 'Legacy conversation')
                """);
        PreparedStatement turn =
            connection.prepareStatement(
                """
                INSERT INTO review_agent_conversation_turns
                  (change_id, user_id, conversation_id, turn_index, user_message_id,
                   turn_metadata_json)
                VALUES (?, 0, 'legacy-conversation', 0, ?, '{}')
                """)) {
      conversation.setString(1, CHANGE_ID);
      conversation.executeUpdate();
      turn.setString(1, CHANGE_ID);
      turn.setLong(2, messageId);
      turn.executeUpdate();
    }
  }

  private String readResource(String resourceName) throws Exception {
    try (InputStream resource = getClass().getClassLoader().getResourceAsStream(resourceName)) {
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8).trim();
    }
  }
}
