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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.http.HttpResponseFor;
import com.openai.models.conversations.Conversation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponse;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.jsonToClass;

@Slf4j
public class OpenAiConversation {
  public static final String KEY_CONVERSATION_ID = "conversationId";

  private final Configuration config;
  private final PluginDataHandler changeDataHandler;
  private final String conversationKey;

  public OpenAiConversation(
      Configuration config,
      PluginDataHandlerProvider pluginDataHandlerProvider) {
    this(config, pluginDataHandlerProvider, KEY_CONVERSATION_ID);
  }

  public OpenAiConversation(
      Configuration config,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      String conversationKey) {
    this.config = config;
    this.conversationKey = conversationKey;
    changeDataHandler = pluginDataHandlerProvider.getChangeScope();
  }

  public static String getMultiAgentConversationKey(ReviewAssistantStages assistantStage) {
    return KEY_CONVERSATION_ID + "." + assistantStage.name().toLowerCase(Locale.ROOT);
  }

  public String resolveConversationId() throws AiConnectionFailException {
    String conversationId = getExistingConversationId();
    if (conversationId == null) {
      return createConversation();
    }
    log.info(
        "Existing OpenAI conversation found for the Change Set. Conversation ID: {}",
        conversationId);
    return conversationId;
  }

  public boolean hasExistingConversation() {
    return getExistingConversationId() != null;
  }

  private String getExistingConversationId() {
    return changeDataHandler.getValue(conversationKey);
  }

  private String createConversation() throws AiConnectionFailException {
    log.debug("OpenAI Create Conversation request: {}", "{}");

    OpenAIClient client = OpenAiSdkClientFactory.create(config);
    try {
      try (HttpResponseFor<Conversation> response =
          client.conversations().withRawResponse().create()) {
        String responseBody = OpenAiSdkClientFactory.readBody(response);
        OpenAiResponse conversationResponse = jsonToClass(responseBody, OpenAiResponse.class);
        String conversationId = conversationResponse.getId();
        if (conversationId != null) {
          changeDataHandler.setValue(conversationKey, conversationId);
          log.info("Conversation created: {}", conversationResponse);
        } else {
          log.error("Failed to create conversation. Response: {}", conversationResponse);
        }
        return conversationId;
      }
    } catch (Exception e) {
      throw new AiConnectionFailException(
          String.format(
              "OpenAI conversation creation failed against `%s`: %s",
              OpenAiSdkClientFactory.getResolvedBaseUrl(config),
              OpenAiSdkClientFactory.describeException(e)),
          e);
    } finally {
      client.close();
    }
  }

  public void clear() {
    changeDataHandler.removeValue(KEY_CONVERSATION_ID);
    for (ReviewAssistantStages assistantStage : ReviewAssistantStages.values()) {
      changeDataHandler.removeValue(getMultiAgentConversationKey(assistantStage));
    }
  }
}
