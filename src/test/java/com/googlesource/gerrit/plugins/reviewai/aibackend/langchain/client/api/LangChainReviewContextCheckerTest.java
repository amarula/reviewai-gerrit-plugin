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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class LangChainReviewContextCheckerTest {
  private static final Path REVIEW_HISTORY_RESOURCE =
      Path.of("src/test/resources/__files/langchain/routerAiDataPromptWithHistory.json");

  @Test
  public void detectsExistingOpenAiReviewConversation() {
    Configuration config = mock(Configuration.class);
    when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
    PluginDataHandler changeDataHandler = mock(PluginDataHandler.class);
    when(changeDataHandler.getValue(
            OpenAiConversation.getMultiAgentConversationKey(ReviewAssistantStage.REVIEW_CODE)))
        .thenReturn("conv_review_code");
    PluginDataHandlerProvider pluginDataHandlerProvider = mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);

    boolean existing =
        new LangChainReviewContextChecker(config, pluginDataHandlerProvider, false)
            .hasExistingReviewContext(new ChangeSetData(1, -1, 1));

    assertTrue(existing);
  }

  @Test
  public void requiresScopeForOpenAiMultiAgentReviewContext() {
    Configuration config = mock(Configuration.class);
    when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
    PluginDataHandler changeDataHandler = mock(PluginDataHandler.class);
    when(changeDataHandler.getValue(
            OpenAiConversation.getMultiAgentConversationKey(ReviewAssistantStage.REVIEW_CODE)))
        .thenReturn("conv_review_code");
    PluginDataHandlerProvider pluginDataHandlerProvider = mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);

    boolean existing =
        new LangChainReviewContextChecker(config, pluginDataHandlerProvider, true)
            .hasExistingReviewContext(new ChangeSetData(1, -1, 1));

    assertFalse(existing);
  }

  @Test
  public void detectsExistingReviewInNonOpenAiContextHistory() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getAiProviderType()).thenReturn(AiProviderType.GEMINI);
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setAiDataPrompt(Files.readString(REVIEW_HISTORY_RESOURCE));

    boolean existing =
        new LangChainReviewContextChecker(config, null, false)
            .hasExistingReviewContext(changeSetData);

    assertTrue(existing);
  }
}
