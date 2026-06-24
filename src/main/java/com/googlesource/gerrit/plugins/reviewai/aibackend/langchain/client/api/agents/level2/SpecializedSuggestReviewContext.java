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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.google.gson.JsonSyntaxException;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.messages.LangChainChatMessages;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class SpecializedSuggestReviewContext {
  private static final String PREVIOUS_REVIEW_REPLIES_TITLE = "# Previous review replies";

  private final Configuration config;

  SpecializedSuggestReviewContext(Configuration config) {
    this.config = config;
  }

  boolean shouldUsePreviousReviewsAsSuggestContext(ChangeSetData changeSetData) {
    return config != null
        && config.getAiProviderType() == AiProviderType.OPENAI
        && !config.getAiProviderZdr()
        && !buildPreviousReviewMessages(changeSetData).isEmpty();
  }

  String appendPreviousReviewsContext(ChangeSetData changeSetData, String patchSet) {
    List<String> previousReviews =
        buildPreviousReviewMessages(changeSetData).stream()
            .map(LangChainChatMessages::content)
            .map(String::trim)
            .filter(message -> !message.isBlank())
            .toList();
    if (previousReviews.isEmpty()) {
      return patchSet;
    }
    return patchSet
        + "\n\n"
        + PREVIOUS_REVIEW_REPLIES_TITLE
        + "\n"
        + String.join("\n\n---\n\n", previousReviews);
  }

  private List<ChatMessage> buildPreviousReviewMessages(ChangeSetData changeSetData) {
    return buildReviewHistoryMessages(changeSetData == null ? null : changeSetData.getAiDataPrompt())
        .stream()
        .filter(AiMessage.class::isInstance)
        .toList();
  }

  private List<ChatMessage> buildReviewHistoryMessages(String requestData) {
    try {
      return LangChainChatMessages.fromRequestData(requestData);
    } catch (JsonSyntaxException e) {
      log.debug("Unable to parse suggest review context request data as message history", e);
      return List.of();
    }
  }
}
