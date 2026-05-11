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

package com.googlesource.gerrit.plugins.reviewai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import com.googlesource.gerrit.plugins.reviewai.utils.HashUtils;
import com.googlesource.gerrit.plugins.reviewai.utils.TimeUtils;
import com.googlesource.gerrit.plugins.reviewai.web.ReviewAgentConversationStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class PatchSetReviewConversationRecorder {
  private final ChangeSetData changeSetData;
  private final ReviewAgentConversationStore conversationStore;

  @Inject
  PatchSetReviewConversationRecorder(
      ChangeSetData changeSetData, ReviewAgentConversationStore conversationStore) {
    this.changeSetData = changeSetData;
    this.conversationStore = conversationStore;
  }

  void record(GerritChange change, List<ReviewBatch> reviewBatches, Integer reviewScore) {
    if (!"patchset-created".equals(change.getEventType())) {
      return;
    }
    Optional<Integer> changeNumber = change.getChangeNumber();
    if (changeNumber.isEmpty()) {
      log.debug(
          "Skipping ReviewAI comments conversation recording because change number is unavailable for {}",
          change.getFullChangeId());
      return;
    }
    Long timestampMillis = TimeUtils.epochSecondsToMillisOrNow(change.getEventTimeStamp());
    conversationStore.appendTurn(
        change.getFullChangeId(),
        reviewAiCommentsConversationId(changeNumber.get()),
        "ReviewAI comments",
        buildAutomaticReviewTurn(reviewBatches, reviewScore, timestampMillis),
        timestampMillis);
  }

  private JsonObject buildAutomaticReviewTurn(
      List<ReviewBatch> reviewBatches, Integer reviewScore, Long timestampMillis) {
    JsonObject turn = new JsonObject();
    JsonObject userInput = new JsonObject();
    userInput.addProperty(
        "user_question", "Patch set commit event triggered this ReviewAI request.");
    turn.add("user_input", userInput);
    turn.add(
        "response",
        buildChatResponse(buildReviewResponseText(reviewBatches, reviewScore), timestampMillis));
    turn.addProperty("regeneration_index", 0);
    if (timestampMillis != null) {
      turn.addProperty("timestamp_millis", timestampMillis);
    }
    return turn;
  }

  private JsonObject buildChatResponse(String responseText, Long timestampMillis) {
    JsonObject response = new JsonObject();
    JsonArray responseParts = new JsonArray();
    JsonObject responsePart = new JsonObject();
    responsePart.addProperty("id", 0);
    responsePart.addProperty("text", responseText);
    responseParts.add(responsePart);
    response.add("response_parts", responseParts);
    response.add("references", new JsonArray());
    response.add("citations", new JsonArray());
    if (timestampMillis != null) {
      response.addProperty("timestamp_millis", timestampMillis);
    }
    return response;
  }

  private String buildReviewResponseText(List<ReviewBatch> reviewBatches, Integer reviewScore) {
    List<String> parts = new ArrayList<>();
    if (reviewScore != null) {
      parts.add("**Code-Review " + formatReviewScore(reviewScore) + "**");
    }
    for (ReviewBatch reviewBatch : reviewBatches) {
      String text = Optional.ofNullable(reviewBatch.getContent()).orElse("").trim();
      if (text.isEmpty()) {
        continue;
      }
      String location = getReviewBatchLocation(reviewBatch);
      parts.add(location == null ? text : "**" + location + "**\n" + text);
    }
    if (parts.isEmpty()) {
      String systemMessage = changeSetData.getReviewSystemMessage();
      if (systemMessage != null && !systemMessage.isBlank()) {
        parts.add(systemMessage.trim());
      }
    }
    return String.join("\n\n", parts);
  }

  private String getReviewBatchLocation(ReviewBatch reviewBatch) {
    String filename = reviewBatch.getFilename();
    if (filename == null || filename.isBlank()) {
      return null;
    }
    if (reviewBatch.getLine() == null) {
      return filename;
    }
    return filename + ":" + reviewBatch.getLine();
  }

  private String formatReviewScore(Integer reviewScore) {
    return reviewScore > 0 ? "+" + reviewScore : String.valueOf(reviewScore);
  }

  private String reviewAiCommentsConversationId(int changeNumber) {
    return HashUtils.stableUuid("reviewai-" + changeNumber);
  }
}
