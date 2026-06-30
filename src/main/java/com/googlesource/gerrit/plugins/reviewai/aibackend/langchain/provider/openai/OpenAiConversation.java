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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai;

import com.openai.client.OpenAIClient;
import com.openai.core.http.HttpResponseFor;
import com.openai.models.conversations.Conversation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.model.OpenAiResponse;
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
  private static final String MESSAGES_CONVERSATION_KEY = KEY_CONVERSATION_ID + ".messages";
  private static final String SPECIALIZED_AGENT_CONVERSATION_KEY_PREFIX =
      KEY_CONVERSATION_ID + ".review_specialized_agent.";
  private static final String SUGGEST_CONVERSATION_KEY_PREFIX = KEY_CONVERSATION_ID + ".suggest";

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

  public static String getMultiAgentConversationKey(ReviewAssistantStage assistantStage) {
    return KEY_CONVERSATION_ID + "." + assistantStage.name().toLowerCase(Locale.ROOT);
  }

  public static String getMultiAgentConversationKey(
      ReviewAssistantStage assistantStage, String conversationSuffix) {
    String conversationKey = getMultiAgentConversationKey(assistantStage);
    if (conversationSuffix == null || conversationSuffix.isBlank()) {
      return conversationKey;
    }
    return conversationKey + "." + conversationSuffix.trim().toLowerCase(Locale.ROOT);
  }

  public static String getMessagesConversationKey() {
    return MESSAGES_CONVERSATION_KEY;
  }

  public static String getSpecializedAgentConversationKey(String agentName) {
    String normalizedAgentName =
        agentName == null ? "" : agentName.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
    return SPECIALIZED_AGENT_CONVERSATION_KEY_PREFIX + normalizedAgentName;
  }

  public static String getSuggestConversationKey(ReviewScope reviewScope) {
    String scope =
        reviewScope == null ? "full" : reviewScope.name().toLowerCase(Locale.ROOT);
    return SUGGEST_CONVERSATION_KEY_PREFIX + "." + scope;
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

  public void clearCurrentConversation() {
    changeDataHandler.removeValue(conversationKey);
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
    changeDataHandler.removeValue(MESSAGES_CONVERSATION_KEY);
    changeDataHandler.getAllValues().keySet().stream()
        .filter(key -> key.startsWith(KEY_CONVERSATION_ID + ".review_"))
        .forEach(changeDataHandler::removeValue);
    changeDataHandler.getAllValues().keySet().stream()
        .filter(key -> key.startsWith(SPECIALIZED_AGENT_CONVERSATION_KEY_PREFIX))
        .forEach(changeDataHandler::removeValue);
    changeDataHandler.getAllValues().keySet().stream()
        .filter(key -> key.startsWith(SUGGEST_CONVERSATION_KEY_PREFIX))
        .forEach(changeDataHandler::removeValue);
  }
}
