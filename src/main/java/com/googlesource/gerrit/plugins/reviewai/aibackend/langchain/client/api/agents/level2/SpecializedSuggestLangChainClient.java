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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;

class SpecializedSuggestLangChainClient extends LangChainClient {
  private final PluginDataHandlerProvider pluginDataHandlerProvider;

  SpecializedSuggestLangChainClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      GerritClient gerritClient,
      Localizer localizer,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      PluginChatMemoryStore chatMemoryStore,
      GitRepoFiles gitRepoFiles,
      ReviewAiMetrics metrics) {
    super(
        config,
        codeContextPolicy,
        gerritClient,
        localizer,
        pluginDataHandlerProvider,
        chatMemoryStore,
        gitRepoFiles,
        metrics);
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
  }

  @Override
  protected ConversationResolution resolveConversation(
      AiProviderType providerType, ChangeSetData changeSetData) throws AiConnectionFailException {
    if (!changeSetData.getSuggestMode()
        || !shouldUseOpenAiResponses(providerType)
        || pluginDataHandlerProvider == null) {
      return super.resolveConversation(providerType, changeSetData);
    }
    OpenAiConversation conversation =
        new OpenAiConversation(
            config,
            pluginDataHandlerProvider,
            OpenAiConversation.getSuggestConversationKey(changeSetData.getReviewScope()));
    boolean existingConversation = conversation.hasExistingConversation();
    return conversationResolution(conversation.resolveConversationId(), existingConversation);
  }
}
