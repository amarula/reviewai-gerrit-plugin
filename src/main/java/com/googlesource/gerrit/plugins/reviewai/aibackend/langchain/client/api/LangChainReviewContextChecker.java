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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

import com.google.gson.JsonSyntaxException;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiMessageItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiRequestMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class LangChainReviewContextChecker {
  private final Configuration config;
  private final PluginDataHandlerProvider pluginDataHandlerProvider;
  private final boolean requireOpenAiScope;

  LangChainReviewContextChecker(
      Configuration config,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      boolean requireOpenAiScope) {
    this.config = config;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.requireOpenAiScope = requireOpenAiScope;
  }

  boolean hasExistingReviewContext(ChangeSetData changeSetData) {
    if (config == null) {
      return false;
    }
    if (config.getAiProviderType() == AiProviderType.OPENAI) {
      return hasExistingOpenAiReviewContext(changeSetData);
    }
    return hasAssistantHistory(changeSetData.getAiDataPrompt());
  }

  private boolean hasExistingOpenAiReviewContext(ChangeSetData changeSetData) {
    if (pluginDataHandlerProvider == null
        || requireOpenAiScope && changeSetData.getReviewScope() == null) {
      return false;
    }
    return new OpenAiConversation(
            config, pluginDataHandlerProvider, LangChainOpenAiConversationKey.from(changeSetData))
        .hasExistingConversation();
  }

  private boolean hasAssistantHistory(String requestData) {
    if (requestData == null || requestData.isBlank()) {
      return false;
    }
    try {
      AiMessageItem[] messageItems = getGson().fromJson(requestData, AiMessageItem[].class);
      if (messageItems == null) {
        return false;
      }
      for (AiMessageItem messageItem : messageItems) {
        if (messageItem == null || messageItem.getHistory() == null) {
          continue;
        }
        for (AiRequestMessage message : messageItem.getHistory()) {
          if (message != null
              && Settings.OPENAI_ROLE_ASSISTANT.equals(message.getRole())
              && message.getContent() != null
              && !message.getContent().isBlank()) {
            return true;
          }
        }
      }
    } catch (JsonSyntaxException e) {
      log.debug("Unable to parse request data while checking for existing review context", e);
    }
    return false;
  }
}
