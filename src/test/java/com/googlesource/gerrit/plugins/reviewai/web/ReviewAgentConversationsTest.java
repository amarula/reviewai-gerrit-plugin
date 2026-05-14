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

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.PatchSet;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.web.model.ReviewAgentConversationInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static com.googlesource.gerrit.plugins.reviewai.utils.JdbcUtils.hasColumn;
import static com.googlesource.gerrit.plugins.reviewai.utils.JdbcUtils.hasTable;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

@RunWith(MockitoJUnitRunner.class)
public class ReviewAgentConversationsTest extends TestBase {
  @Mock private ChangeResource changeResource;
  @Mock private AiReviewPermission aiReviewPermission;

  private ReviewAgentConversations view;
  private ReviewAgentConversationStore conversationStore;
  private String jdbcUrl;

  @Before
  public void setUp() throws Exception {
    Change change = new Change(CHANGE_ID, Change.id(1), Account.id(100), BRANCH_NAME, Instant.now());
    change.setCurrentPatchSet(PatchSet.id(change.getId(), 1), "", "");
    when(changeResource.getChange()).thenReturn(change);
    jdbcUrl = "jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    conversationStore = new ReviewAgentConversationStore(jdbcUrl, tempFolder.getRoot().toPath());
    view =
        new ReviewAgentConversations(
            conversationStore, aiReviewPermission);
  }

  @Test
  public void storesAndReadsConversationFromChangeScopedPluginData() throws Exception {
    ReviewAgentConversationInfo conversation = conversation("conversation-1", 1000L);
    ReviewAgentConversations.Input upsertInput = new ReviewAgentConversations.Input();
    upsertInput.action = "upsert";
    upsertInput.conversation = conversation;

    view.apply(changeResource, upsertInput);

    ReviewAgentConversations.Input getInput = new ReviewAgentConversations.Input();
    getInput.action = "get";
    getInput.conversationId = conversation.id;
    Response<ReviewAgentConversations.Output> getResponse = view.apply(changeResource, getInput);

    assertNotNull(getResponse.value().conversation);
    assertEquals(uuidFor("conversation-1"), getResponse.value().conversation.id);
    assertEquals("First conversation", getResponse.value().conversation.title);
    assertEquals("Hello", getResponse.value().conversation.turns.get(0).get("message").getAsString());
  }

  @Test
  public void listsConversationsNewestFirst() throws Exception {
    upsert(conversation("older", 1000L));
    upsert(conversation("newer", 2000L));

    ReviewAgentConversations.Input listInput = new ReviewAgentConversations.Input();
    listInput.action = "list";
    List<ReviewAgentConversationInfo> conversations =
        view.apply(changeResource, listInput).value().conversations;

    assertEquals(uuidFor("newer"), conversations.get(0).id);
    assertEquals(uuidFor("older"), conversations.get(1).id);
  }

  @Test
  public void readsAndUpdatesConversationIgnoringIdCase() throws Exception {
    upsert(conversation("36b8f84d-df4e-4d49-b662-bcde71a8764f", 1000L));

    ReviewAgentConversations.Input getInput = new ReviewAgentConversations.Input();
    getInput.action = "get";
    getInput.conversationId = "36B8F84D-DF4E-4D49-B662-BCDE71A8764F";
    ReviewAgentConversationInfo storedConversation =
        view.apply(changeResource, getInput).value().conversation;

    assertNotNull(storedConversation);
    assertEquals("36b8f84d-df4e-4d49-b662-bcde71a8764f", storedConversation.id);

    ReviewAgentConversationInfo updatedConversation =
        conversation("36B8F84D-DF4E-4D49-B662-BCDE71A8764F", 2000L);
    upsert(updatedConversation);

    ReviewAgentConversations.Input listInput = new ReviewAgentConversations.Input();
    listInput.action = "list";
    List<ReviewAgentConversationInfo> conversations =
        view.apply(changeResource, listInput).value().conversations;

    assertEquals(1, conversations.size());
    assertEquals("36b8f84d-df4e-4d49-b662-bcde71a8764f", conversations.get(0).id);
  }

  @Test
  public void appendsTurnToExistingConversation() throws Exception {
    append("conversation-1", 0, turn("First", "First response"), 1000L);
    append("conversation-1", 1, turn("Second", "Second response"), 2000L);

    ReviewAgentConversationInfo conversation = get("conversation-1");

    assertEquals(2, conversation.turns.size());
    assertEquals("First", userQuestion(conversation.turns.get(0)));
    assertEquals("Second", userQuestion(conversation.turns.get(1)));
    assertEquals(Long.valueOf(2000L), conversation.timestampMillis);
  }

  @Test
  public void appendingTurnKeepsExistingTurnUpdatedAt() throws Exception {
    append("conversation-1", 0, turn("First", "First response"), 1000L);
    Timestamp firstTurnCreatedAt = Timestamp.valueOf("2026-01-02 03:04:05.123456");
    try (var c = DriverManager.getConnection(jdbcUrl);
        var ps =
            c.prepareStatement(
                "UPDATE review_agent_conversation_turns SET updated_at = ? WHERE turn_index = 0")) {
      ps.setTimestamp(1, firstTurnCreatedAt);
      ps.executeUpdate();
    }

    append("conversation-1", 1, turn("Second", "Second response"), 2000L);

    try (var c = DriverManager.getConnection(jdbcUrl);
        var rs =
            c.createStatement()
                .executeQuery(
                    "SELECT updated_at FROM review_agent_conversation_turns ORDER BY turn_index")) {
      rs.next();
      assertEquals(firstTurnCreatedAt, rs.getTimestamp(1));
      assertEquals(true, rs.next());
    }
  }

  @Test
  public void appendsTurnIgnoringConversationIdCase() throws Exception {
    append("36b8f84d-df4e-4d49-b662-bcde71a8764f", 0, turn("First", "First response"), 1000L);
    append("36B8F84D-DF4E-4D49-B662-BCDE71A8764F", 1, turn("Second", "Second response"), 2000L);

    ReviewAgentConversationInfo conversation = get("36b8f84d-df4e-4d49-b662-bcde71a8764f");

    assertEquals(2, conversation.turns.size());
    assertEquals("First", userQuestion(conversation.turns.get(0)));
    assertEquals("Second", userQuestion(conversation.turns.get(1)));
    assertEquals("36b8f84d-df4e-4d49-b662-bcde71a8764f", conversation.id);
  }

  @Test
  public void appendsDifferentQuestionEvenWhenTurnIndexPointsAtExistingTurn() throws Exception {
    append("conversation-1", 0, turn("/review", "Review response"), 1000L);
    append("conversation-1", 0, turn("/message pls explain", "Explanation response"), 2000L);

    ReviewAgentConversationInfo conversation = get("conversation-1");

    assertEquals(2, conversation.turns.size());
    assertEquals("/review", userQuestion(conversation.turns.get(0)));
    assertEquals("/message pls explain", userQuestion(conversation.turns.get(1)));
  }

  @Test
  public void reviewCommandReplacesPatchSetEventTriggerTurnAtSameIndex() throws Exception {
    append(
        "conversation-1",
        0,
        turn("Patch set commit event triggered this ReviewAI request.", "**Code-Review +1**"),
        1000L);
    append("conversation-1", 0, turn("/review", "Review response"), 2000L);

    ReviewAgentConversationInfo conversation = get("conversation-1");

    assertEquals(1, conversation.turns.size());
    assertEquals("/review", userQuestion(conversation.turns.get(0)));
    assertEquals("Review response", responseText(conversation.turns.get(0)));
    try (var c = DriverManager.getConnection(jdbcUrl);
        var rs =
            c.createStatement()
                .executeQuery("SELECT COUNT(*) FROM review_agent_conversation_turns")) {
      rs.next();
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  public void appendWithExistingTurnIndexReplacesThatTurnOnly() throws Exception {
    append("conversation-1", 0, turn("First", "First response"), 1000L);
    append("conversation-1", 1, turn("Second", "Second response"), 2000L);
    Timestamp secondTurnCreatedAt = Timestamp.valueOf("2026-01-02 03:04:05.123456");
    try (var c = DriverManager.getConnection(jdbcUrl);
        var ps =
            c.prepareStatement(
                "UPDATE review_agent_conversation_turns SET updated_at = ? WHERE turn_index = 1")) {
      ps.setTimestamp(1, secondTurnCreatedAt);
      ps.executeUpdate();
    }

    append("conversation-1", 1, turn("Second", "Second regenerated response"), 3000L);

    ReviewAgentConversationInfo conversation = get("conversation-1");

    assertEquals(2, conversation.turns.size());
    assertEquals("First", userQuestion(conversation.turns.get(0)));
    assertEquals("Second", userQuestion(conversation.turns.get(1)));
    assertEquals("Second regenerated response", responseText(conversation.turns.get(1)));
    try (var c = DriverManager.getConnection(jdbcUrl);
        var ps =
            c.prepareStatement(
                "SELECT updated_at FROM review_agent_conversation_turns WHERE turn_index = 1");
        var rs = ps.executeQuery()) {
      rs.next();
      assertEquals(secondTurnCreatedAt, rs.getTimestamp(1));
    }
  }

  @Test
  public void upsertWithSingleDifferentTurnAppendsToExistingConversation() throws Exception {
    ReviewAgentConversationInfo firstConversation = conversation("conversation-1", 1000L);
    firstConversation.title = "/review";
    firstConversation.turns.clear();
    firstConversation.turns.add(turn("/review", "Review response"));
    upsert(firstConversation);

    ReviewAgentConversationInfo secondConversation = conversation("conversation-1", 2000L);
    secondConversation.title = "/message pls explain";
    secondConversation.turns.clear();
    secondConversation.turns.add(turn("/message pls explain", "Explanation response"));
    upsert(secondConversation);

    ReviewAgentConversationInfo conversation = get("conversation-1");

    assertEquals("/review", conversation.title);
    assertEquals(2, conversation.turns.size());
    assertEquals("/review", userQuestion(conversation.turns.get(0)));
    assertEquals("/message pls explain", userQuestion(conversation.turns.get(1)));
  }

  @Test
  public void upsertWithSingleSameTurnReplacesExistingTurnOnly() throws Exception {
    ReviewAgentConversationInfo firstConversation = conversation("conversation-1", 1000L);
    firstConversation.turns.clear();
    firstConversation.turns.add(turn("/review", "Review response"));
    upsert(firstConversation);

    ReviewAgentConversationInfo regeneratedConversation = conversation("conversation-1", 2000L);
    regeneratedConversation.turns.clear();
    regeneratedConversation.turns.add(turn("/review", "Regenerated review response"));
    upsert(regeneratedConversation);

    ReviewAgentConversationInfo conversation = get("conversation-1");

    assertEquals(1, conversation.turns.size());
    assertEquals("/review", userQuestion(conversation.turns.get(0)));
    assertEquals("Regenerated review response", responseText(conversation.turns.get(0)));
  }

  @Test
  public void appendAcceptsSnakeCaseJsonFieldNames() throws Exception {
    ReviewAgentConversations.Input input =
        getGson()
            .fromJson(
                """
                {
                  "action": "append",
                  "conversation_id": "conversation-1",
                  "timestamp_millis": 1000,
                  "turn_index": 0,
                  "turn": {
                    "user_input": {"user_question": "Hi"},
                    "response": {"response_parts": [{"id": 0, "text": "Hello"}]}
                  }
                }
                """,
                ReviewAgentConversations.Input.class);

    view.apply(changeResource, input);

    ReviewAgentConversationInfo conversation = get("conversation-1");

    assertEquals(1, conversation.turns.size());
    assertEquals("Hi", userQuestion(conversation.turns.get(0)));
    assertEquals(Long.valueOf(1000L), conversation.timestampMillis);
  }

  @Test
  public void storesConversationIdAsUuid() throws Exception {
    append("reviewai-15038", 0, turn("First", "First response"), 1000L);

    try (var c = DriverManager.getConnection(jdbcUrl);
        var rs =
            c.createStatement()
                .executeQuery("SELECT conversation_id FROM review_agent_conversations")) {
      rs.next();
      String conversationId = rs.getString(1);
      UUID.fromString(conversationId);
      assertEquals(uuidFor("reviewai-15038"), conversationId);
    }
  }

  @Test
  public void storesCompleteTurnContentInConversationTurnRows() throws Exception {
    append("conversation-1", 0, turn("First", "First response"), 1000L);

    try (var c = DriverManager.getConnection(jdbcUrl);
        var turnContent =
            c.createStatement()
                .executeQuery(
                    "SELECT user_message_id, turn_metadata_json "
                        + "FROM review_agent_conversation_turns")) {
      turnContent.next();
      assertEquals(null, turnContent.getObject(1));
      assertEquals(true, turnContent.getString(2).contains("\"user_question\":\"First\""));
      assertEquals(true, turnContent.getString(2).contains("First response"));
      assertFalse(hasTable(c, "LANGCHAIN_CHAT_MEMORY_MESSAGES"));
      assertFalse(hasColumn(c, "REVIEW_AGENT_CONVERSATION_TURNS", "TURN_CONTENT_JSON"));
      assertFalse(hasResponsePartsTable(c));
    }
  }

  @Test
  public void storesEmptyOrForgetThreadTurnsOnlyInConversationTurnRows() throws Exception {
    append("conversation-1", 0, turn("", "Empty prompt response"), 1000L);
    append("conversation-1", 1, turn("/forget_thread", "Forget response"), 2000L);

    try (var c = DriverManager.getConnection(jdbcUrl);
        var rs =
            c.createStatement()
                .executeQuery(
                    "SELECT turn_metadata_json FROM review_agent_conversation_turns "
                        + "ORDER BY turn_index")) {
      rs.next();
      assertEquals(true, rs.getString(1).contains("Empty prompt response"));
      rs.next();
      assertEquals(true, rs.getString(1).contains("\"user_question\":\"/forget_thread\""));
      assertEquals(false, rs.next());
      assertFalse(hasTable(c, "LANGCHAIN_CHAT_MEMORY_MESSAGES"));
    }
  }

  @Test
  public void migratesLegacyConversationDataFileToDb() throws Exception {
    ReviewAgentConversationInfo conversation = conversation("legacy-conversation", 1000L);
    Properties legacyProperties = new Properties();
    legacyProperties.setProperty(
        "reviewAgentConversations", getGson().toJson(Map.of(conversation.id, conversation)));
    try (var output =
        Files.newOutputStream(tempFolder.getRoot().toPath().resolve(CHANGE_ID + ".data"))) {
      legacyProperties.store(output, null);
    }

    ReviewAgentConversationInfo restored = get("legacy-conversation");

    assertNotNull(restored);
    assertEquals(uuidFor("legacy-conversation"), restored.id);
    assertEquals("Hello", restored.turns.get(0).get("message").getAsString());
  }

  @Test
  public void removesActionClientDataFromAutomaticReviewTurns() throws Exception {
    JsonObject automaticReviewTurn =
        turn("Patch set commit event triggered this ReviewAI request.", "Code-Review -1");
    automaticReviewTurn
        .getAsJsonObject("user_input")
        .addProperty(
            "client_data",
            """
            {"overridesPreviousTurn":true,"actionId":"review-change","contextItems":[],"isBackgroundRequest":true}
            """);
    append("conversation-1", 0, automaticReviewTurn, 1000L);

    ReviewAgentConversationInfo conversation = get("conversation-1");

    assertFalse(conversation.turns.get(0).getAsJsonObject("user_input").has("client_data"));
  }

  @Test
  public void getsAutomaticReviewResponseTexts() throws Exception {
    String responseText = readTestResource("__files/review-agent/automaticReviewResponse.txt");
    append(
        "conversation-1",
        0,
        turn("Patch set commit event triggered this ReviewAI request.", responseText),
        1000L);
    append("conversation-1", 1, turn("/review", "Manual review response"), 2000L);

    assertEquals(
        List.of(responseText),
        conversationStore.getAutomaticReviewResponseTexts(getGerritChange().getFullChangeId()));
  }

  @Test
  public void getWithoutConversationIdReturnsEmptyConversation() throws Exception {
    ReviewAgentConversations.Input getInput = new ReviewAgentConversations.Input();
    getInput.action = "get";

    assertEquals(null, view.apply(changeResource, getInput).value().conversation);
  }

  @Test(expected = AuthException.class)
  public void rejectsConversationAccessWhenAiReviewIsNotAllowed() throws Exception {
    ReviewAgentConversations.Input listInput = new ReviewAgentConversations.Input();
    listInput.action = "list";
    org.mockito.Mockito.doThrow(new AuthException("AI review is not allowed for this change"))
        .when(aiReviewPermission)
        .checkCanAiReview(changeResource);

    view.apply(changeResource, listInput);
  }

  private ReviewAgentConversationInfo get(String conversationId) throws Exception {
    ReviewAgentConversations.Input input = new ReviewAgentConversations.Input();
    input.action = "get";
    input.conversationId = conversationId;
    return view.apply(changeResource, input).value().conversation;
  }

  private void append(String conversationId, int turnIndex, JsonObject turn, Long timestampMillis)
      throws Exception {
    ReviewAgentConversations.Input input = new ReviewAgentConversations.Input();
    input.action = "append";
    input.conversationId = conversationId;
    input.title = "Appended conversation";
    input.turnIndex = turnIndex;
    input.timestampMillis = timestampMillis;
    input.turn = turn;
    view.apply(changeResource, input);
  }

  private void upsert(ReviewAgentConversationInfo conversation) throws Exception {
    ReviewAgentConversations.Input input = new ReviewAgentConversations.Input();
    input.action = "upsert";
    input.conversation = conversation;
    view.apply(changeResource, input);
  }

  private ReviewAgentConversationInfo conversation(String id, Long timestampMillis) {
    ReviewAgentConversationInfo conversation = new ReviewAgentConversationInfo();
    conversation.id = id;
    conversation.title = "First conversation";
    conversation.timestampMillis = timestampMillis;
    conversation.turns.add(turn("Hello"));
    return conversation;
  }

  private JsonObject turn(String message) {
    JsonObject turn = new JsonObject();
    turn.addProperty("message", message);
    return turn;
  }

  private JsonObject turn(String userQuestion, String responseText) {
    JsonObject turn = new JsonObject();
    JsonObject userInput = new JsonObject();
    userInput.addProperty("user_question", userQuestion);
    turn.add("user_input", userInput);
    JsonObject response = new JsonObject();
    JsonObject responsePart = new JsonObject();
    responsePart.addProperty("id", 0);
    responsePart.addProperty("text", responseText);
    JsonArray responseParts = new JsonArray();
    responseParts.add(responsePart);
    response.add("response_parts", responseParts);
    turn.add("response", response);
    return turn;
  }

  private String userQuestion(JsonObject turn) {
    return turn.getAsJsonObject("user_input").get("user_question").getAsString();
  }

  private String responseText(JsonObject turn) {
    return turn.getAsJsonObject("response")
        .getAsJsonArray("response_parts")
        .get(0)
        .getAsJsonObject()
        .get("text")
        .getAsString();
  }

  private String readTestResource(String resourceName) throws Exception {
    return Files.readString(Paths.get("src/test/resources").resolve(resourceName)).trim();
  }

  private boolean hasResponsePartsTable(java.sql.Connection c) throws Exception {
    try (var rs =
        c.getMetaData()
            .getTables(null, null, "REVIEW_AGENT_CONVERSATION_RESPONSE_PARTS", null)) {
      return rs.next();
    }
  }

  private String uuidFor(String conversationId) {
    return UUID.nameUUIDFromBytes(
            conversationId.toLowerCase().getBytes(StandardCharsets.UTF_8))
        .toString();
  }
}
