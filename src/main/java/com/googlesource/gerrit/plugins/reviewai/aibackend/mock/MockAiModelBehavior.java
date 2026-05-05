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

package com.googlesource.gerrit.plugins.reviewai.aibackend.mock;

import com.google.gson.annotations.SerializedName;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class MockAiModelBehavior {
  @SerializedName(value = "delay_ms", alternate = {"delayMs"})
  private Long delayMs;

  @SerializedName(value = "delay_seconds", alternate = {"delaySeconds"})
  private Double delaySeconds;

  @SerializedName(value = "response_text", alternate = {"responseText", "response"})
  private String responseText;

  @SerializedName(value = "score", alternate = {"vote"})
  private Double score;

  private Double relevance;
  private List<AiReplyItem> replies;

  @SerializedName(
      value = "json_response",
      alternate = {"jsonResponse", "response_json", "responseJson"})
  private AiResponseContent jsonResponse;

  @SerializedName(value = "message_content", alternate = {"messageContent"})
  private String messageContent;

  public long getResolvedDelayMs() {
    if (delayMs != null) {
      return Math.max(0, delayMs);
    }
    if (delaySeconds != null) {
      return Math.max(0, Math.round(delaySeconds * 1000));
    }
    return 0;
  }

  public AiResponseContent toResponseContent() {
    if (jsonResponse != null) {
      return jsonResponse;
    }
    if (replies != null && !replies.isEmpty()) {
      AiResponseContent content = new AiResponseContent(emptyIfNull(messageContent));
      content.setReplies(replies);
      return content;
    }
    if (score != null) {
      AiResponseContent content = new AiResponseContent("");
      List<AiReplyItem> resolvedReplies = new ArrayList<>();
      resolvedReplies.add(
          AiReplyItem.builder()
              .reply(emptyIfNull(firstNonBlank(responseText, messageContent)))
              .score(score)
              .relevance(relevance)
              .build());
      content.setReplies(resolvedReplies);
      return content;
    }
    return new AiResponseContent(emptyIfNull(firstNonBlank(messageContent, responseText)));
  }

  private String firstNonBlank(String first, String second) {
    return first != null && !first.isBlank() ? first : second;
  }

  private String emptyIfNull(String value) {
    return value == null ? "" : value;
  }
}
