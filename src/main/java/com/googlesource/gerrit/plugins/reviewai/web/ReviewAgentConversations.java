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

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getObject;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getString;

import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.web.model.ReviewAgentConversationInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ReviewAgentConversations
    implements RestModifyView<ChangeResource, ReviewAgentConversations.Input> {
  private static final String ACTION_LIST = "list";
  private static final String ACTION_GET = "get";
  private static final String ACTION_UPSERT = "upsert";
  private static final String ACTION_APPEND = "append";
  private static final String PATCH_SET_EVENT_TRIGGER_MESSAGE =
      "Patch set commit event triggered this ReviewAI request.";

  private final ReviewAgentConversationStore conversationStore;
  private final AiReviewPermission aiReviewPermission;

  @Inject
  ReviewAgentConversations(
      ReviewAgentConversationStore conversationStore,
      AiReviewPermission aiReviewPermission) {
    this.conversationStore = conversationStore;
    this.aiReviewPermission = aiReviewPermission;
  }

  @Override
  public Response<Output> apply(ChangeResource resource, Input input) throws Exception {
    aiReviewPermission.checkCanAiReview(resource);
    if (input == null || input.action == null || input.action.isBlank()) {
      throw new BadRequestException("action is required");
    }

    String changeId = getFullChangeId(resource);
    Map<String, ReviewAgentConversationInfo> conversations =
        conversationStore.getConversations(changeId);

    return switch (input.action) {
      case ACTION_LIST -> Response.ok(Output.list(toSortedList(conversations)));
      case ACTION_GET -> Response.ok(Output.conversation(getConversation(conversations, input)));
      case ACTION_APPEND ->
          Response.ok(Output.conversation(append(changeId, conversations, input)));
      case ACTION_UPSERT ->
          Response.ok(Output.conversation(upsert(changeId, conversations, input)));
      default -> throw new BadRequestException("unsupported action: " + input.action);
    };
  }

  private String getFullChangeId(ChangeResource resource) {
    return new GerritChange(
            resource.getChange().getProject(),
            resource.getChange().getDest(),
            resource.getChange().getKey())
        .getFullChangeId();
  }

  private List<ReviewAgentConversationInfo> toSortedList(
      Map<String, ReviewAgentConversationInfo> conversations) {
    List<ReviewAgentConversationInfo> result = new ArrayList<>(conversations.values());
    result.sort(
        Comparator.comparing(
                (ReviewAgentConversationInfo conversation) ->
                    Optional.ofNullable(conversation.timestampMillis).orElse(0L))
            .reversed());
    return result;
  }

  private ReviewAgentConversationInfo getConversation(
      Map<String, ReviewAgentConversationInfo> conversations, Input input) {
    return getConversationById(conversations, input.conversationId);
  }

  private ReviewAgentConversationInfo getConversationById(
      Map<String, ReviewAgentConversationInfo> conversations, String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return null;
    }
    String canonicalConversationId =
        ReviewAgentConversationStore.canonicalConversationId(conversationId);
    ReviewAgentConversationInfo conversation = conversations.get(canonicalConversationId);
    if (conversation != null) {
      return conversation;
    }
    return conversations.entrySet().stream()
        .filter(entry -> entry.getKey().equalsIgnoreCase(canonicalConversationId))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);
  }

  private ReviewAgentConversationInfo upsert(
      String changeId,
      Map<String, ReviewAgentConversationInfo> conversations,
      Input input)
      throws BadRequestException {
    ReviewAgentConversationInfo incomingConversation = input.conversation;
    if (incomingConversation == null) {
      throw new BadRequestException("conversation is required");
    }
    if (incomingConversation.id == null || incomingConversation.id.isBlank()) {
      throw new BadRequestException("conversation.id is required");
    }
    incomingConversation.id =
        ReviewAgentConversationStore.canonicalConversationId(incomingConversation.id);
    ReviewAgentConversationInfo conversation =
        mergeConversation(
            getConversationById(conversations, incomingConversation.id), incomingConversation);
    conversations.entrySet().removeIf(entry -> entry.getKey().equalsIgnoreCase(conversation.id));
    conversations.put(conversation.id, conversation);
    conversationStore.upsertConversation(changeId, conversation);
    return conversation;
  }

  private ReviewAgentConversationInfo append(
      String changeId,
      Map<String, ReviewAgentConversationInfo> conversations,
      Input input)
      throws BadRequestException {
    if (input.conversationId == null || input.conversationId.isBlank()) {
      throw new BadRequestException("conversationId is required");
    }
    if (input.turn == null) {
      throw new BadRequestException("turn is required");
    }
    input.conversationId =
        ReviewAgentConversationStore.canonicalConversationId(input.conversationId);

    ReviewAgentConversationInfo conversation =
        Optional.ofNullable(getConversationById(conversations, input.conversationId))
            .orElseGet(
                () -> {
                  ReviewAgentConversationInfo newConversation = new ReviewAgentConversationInfo();
                  newConversation.id = input.conversationId;
                  return newConversation;
                });

    if (conversation.id == null || conversation.id.isBlank()) {
      conversation.id = input.conversationId;
    }
    if (conversation.title == null || conversation.title.isBlank()) {
      conversation.title = input.title;
    }
    if (conversation.turns == null) {
      conversation.turns = new ArrayList<>();
    }

    int turnIndex = getTurnIndex(conversation, input);
    boolean replaceTurn = false;
    if (turnIndex < conversation.turns.size()) {
      JsonObject existingTurn = conversation.turns.get(turnIndex);
      if (shouldReplaceConversationTurn(existingTurn, input.turn)) {
        conversation.turns.set(turnIndex, input.turn);
        replaceTurn = true;
      } else {
        conversation.turns.add(input.turn);
      }
    } else {
      conversation.turns.add(input.turn);
    }
    conversation.timestampMillis =
        input.timestampMillis != null ? input.timestampMillis : System.currentTimeMillis();

    conversations.entrySet().removeIf(entry -> entry.getKey().equalsIgnoreCase(conversation.id));
    conversations.put(conversation.id, conversation);
    if (replaceTurn) {
      conversationStore.replaceTurn(
          changeId,
          conversation.id,
          conversation.title,
          input.turn,
          conversation.timestampMillis,
          turnIndex);
    } else {
      conversationStore.appendTurn(
          changeId, conversation.id, conversation.title, input.turn, conversation.timestampMillis);
    }
    return conversation;
  }

  private int getTurnIndex(ReviewAgentConversationInfo conversation, Input input) {
    if (input.turnIndex == null || input.turnIndex < 0) {
      return conversation.turns.size();
    }
    return input.turnIndex;
  }

  private boolean isSameConversationTurn(JsonObject existingTurn, JsonObject newTurn) {
    return getUserQuestion(existingTurn).equals(getUserQuestion(newTurn));
  }

  private boolean shouldReplaceConversationTurn(JsonObject existingTurn, JsonObject newTurn) {
    return isSameConversationTurn(existingTurn, newTurn)
        || (isPatchSetEventTriggerTurn(existingTurn) && isReviewCommandTurn(newTurn));
  }

  private boolean isPatchSetEventTriggerTurn(JsonObject turn) {
    return PATCH_SET_EVENT_TRIGGER_MESSAGE.equals(getUserQuestion(turn));
  }

  private boolean isReviewCommandTurn(JsonObject turn) {
    String normalizedQuestion = getUserQuestion(turn).stripLeading();
    return normalizedQuestion.equals("/review") || normalizedQuestion.startsWith("/review ");
  }

  private String getUserQuestion(JsonObject turn) {
    JsonObject userInput = getObject(turn, "user_input");
    String userQuestion = getString(userInput, "user_question");
    return userQuestion == null ? "" : userQuestion;
  }

  private ReviewAgentConversationInfo mergeConversation(
      ReviewAgentConversationInfo storedConversation,
      ReviewAgentConversationInfo incomingConversation) {
    if (storedConversation == null) {
      if (incomingConversation.turns == null) {
        incomingConversation.turns = new ArrayList<>();
      }
      return incomingConversation;
    }

    if (storedConversation.turns == null) {
      storedConversation.turns = new ArrayList<>();
    }
    if (storedConversation.id == null || storedConversation.id.isBlank()) {
      storedConversation.id = incomingConversation.id;
    }
    if (storedConversation.title == null || storedConversation.title.isBlank()) {
      storedConversation.title = incomingConversation.title;
    }
    if (incomingConversation.timestampMillis != null) {
      storedConversation.timestampMillis = incomingConversation.timestampMillis;
    }

    List<JsonObject> incomingTurns =
        incomingConversation.turns != null ? incomingConversation.turns : List.of();
    incomingTurns.forEach(turn -> mergeTurn(storedConversation.turns, turn));
    return storedConversation;
  }

  private void mergeTurn(List<JsonObject> storedTurns, JsonObject incomingTurn) {
    for (int i = 0; i < storedTurns.size(); i++) {
      if (shouldReplaceConversationTurn(storedTurns.get(i), incomingTurn)) {
        storedTurns.set(i, incomingTurn);
        return;
      }
    }
    storedTurns.add(incomingTurn);
  }

  public static class Input {
    public String action;

    @SerializedName(value = "conversationId", alternate = {"conversation_id"})
    public String conversationId;

    public String title;

    @SerializedName(value = "timestampMillis", alternate = {"timestamp_millis"})
    public Long timestampMillis;

    @SerializedName(value = "turnIndex", alternate = {"turn_index"})
    public Integer turnIndex;

    public JsonObject turn;
    public ReviewAgentConversationInfo conversation;
  }

  public static class Output {
    public final List<ReviewAgentConversationInfo> conversations;
    public final ReviewAgentConversationInfo conversation;

    private Output(
        List<ReviewAgentConversationInfo> conversations, ReviewAgentConversationInfo conversation) {
      this.conversations = conversations;
      this.conversation = conversation;
    }

    public static Output list(List<ReviewAgentConversationInfo> conversations) {
      return new Output(conversations, null);
    }

    public static Output conversation(ReviewAgentConversationInfo conversation) {
      return new Output(null, conversation);
    }
  }
}
