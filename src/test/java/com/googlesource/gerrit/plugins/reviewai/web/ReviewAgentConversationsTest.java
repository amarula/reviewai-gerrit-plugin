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
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerBaseProvider;
import com.googlesource.gerrit.plugins.reviewai.web.model.ReviewAgentConversationInfo;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.when;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

@RunWith(MockitoJUnitRunner.class)
public class ReviewAgentConversationsTest extends TestBase {
  @Mock private ChangeResource changeResource;

  private ReviewAgentConversations view;

  @Before
  public void setUp() {
    Path realChangeDataPath = tempFolder.getRoot().toPath().resolve(CHANGE_ID + ".data");
    when(mockPluginDataPath.resolve(CHANGE_ID + ".data")).thenReturn(realChangeDataPath);
    Change change = new Change(CHANGE_ID, Change.id(1), Account.id(100), BRANCH_NAME, Instant.now());
    when(changeResource.getChange()).thenReturn(change);
    view = new ReviewAgentConversations(new PluginDataHandlerBaseProvider(mockPluginDataPath));
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
    assertEquals("conversation-1", getResponse.value().conversation.id);
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

    assertEquals("newer", conversations.get(0).id);
    assertEquals("older", conversations.get(1).id);
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
  public void appendWithExistingTurnIndexReplacesThatTurnOnly() throws Exception {
    append("conversation-1", 0, turn("First", "First response"), 1000L);
    append("conversation-1", 1, turn("Second", "Second response"), 2000L);
    append("conversation-1", 1, turn("Second", "Second regenerated response"), 3000L);

    ReviewAgentConversationInfo conversation = get("conversation-1");

    assertEquals(2, conversation.turns.size());
    assertEquals("First", userQuestion(conversation.turns.get(0)));
    assertEquals("Second", userQuestion(conversation.turns.get(1)));
    assertEquals("Second regenerated response", responseText(conversation.turns.get(1)));
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
  public void getWithoutConversationIdReturnsEmptyConversation() throws Exception {
    ReviewAgentConversations.Input getInput = new ReviewAgentConversations.Input();
    getInput.action = "get";

    assertEquals(null, view.apply(changeResource, getInput).value().conversation);
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
}
