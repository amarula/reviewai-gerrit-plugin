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

package com.googlesource.gerrit.plugins.reviewai.listener;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.events.Event;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainMultiAgentReviewClient;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderTransport;
import java.lang.reflect.Method;
import org.junit.Test;

public class GerritEventContextModuleTest {

  @Test
  public void selectsMultiAgentLangChainClientWhenEnabled() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getAiProviderTransport()).thenReturn(AiProviderTransport.LANGCHAIN);
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    when(config.getMultiAgentMode()).thenReturn(true);

    GerritEventContextModule module = new GerritEventContextModule(config, mock(Event.class));

    Method getAiClient = GerritEventContextModule.class.getDeclaredMethod("getAiClient");
    getAiClient.setAccessible(true);

    assertEquals(LangChainMultiAgentReviewClient.class, getAiClient.invoke(module));
  }

  @Test
  public void keepsUnifiedLangChainClientWhenMultiAgentModeDisabled() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getAiProviderTransport()).thenReturn(AiProviderTransport.LANGCHAIN);
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    when(config.getMultiAgentMode()).thenReturn(false);

    GerritEventContextModule module = new GerritEventContextModule(config, mock(Event.class));

    Method getAiClient = GerritEventContextModule.class.getDeclaredMethod("getAiClient");
    getAiClient.setAccessible(true);

    assertEquals(LangChainClient.class, getAiClient.invoke(module));
  }
}
