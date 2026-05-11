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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.mockito.Mockito;

public class LangChainClientTest {

  @Test
  public void shouldLoadStructuredResponseFormatFromSchemaResource() throws Exception {
    LangChainClient client = new LangChainClient(null, null, null, null);

    Field field = LangChainClient.class.getDeclaredField("structuredResponseFormat");
    field.setAccessible(true);
    ResponseFormat responseFormat = (ResponseFormat) field.get(client);

    assertNotNull("Structured response format should be loaded", responseFormat);
    assertEquals(ResponseFormatType.JSON, responseFormat.type());

    JsonSchema jsonSchema = responseFormat.jsonSchema();
    assertNotNull(jsonSchema);
    assertEquals("format_replies", jsonSchema.name());
    assertTrue(jsonSchema.rootElement() instanceof JsonObjectSchema);

    JsonObjectSchema root = (JsonObjectSchema) jsonSchema.rootElement();
    assertTrue(root.properties().containsKey("replies"));
    assertTrue(root.properties().containsKey("changeId"));

    JsonArraySchema repliesSchema = (JsonArraySchema) root.properties().get("replies");
    assertNotNull(repliesSchema.items());
    assertTrue(repliesSchema.items() instanceof JsonObjectSchema);
    JsonObjectSchema replyItemSchema = (JsonObjectSchema) repliesSchema.items();
    assertTrue(replyItemSchema.properties().containsKey("reply"));
  }

  @Test
  public void resolvesOpenAiConversationForLangChainOpenAiProvider() throws Exception {
    PluginDataHandler changeDataHandler = Mockito.mock(PluginDataHandler.class);
    when(changeDataHandler.getValue(OpenAiConversation.KEY_CONVERSATION_ID))
        .thenReturn("conv_langchain_openai");
    PluginDataHandlerProvider pluginDataHandlerProvider = Mockito.mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setForcedReview(true);
    changeSetData.setReviewAssistantStage(null);

    String conversationId =
        resolveConversationId(
            new LangChainClient(
                Mockito.mock(Configuration.class), null, null, null, pluginDataHandlerProvider),
            AiProviderType.OPENAI,
            changeSetData);

    assertEquals("conv_langchain_openai", conversationId);
  }

  @Test
  public void resolvesStageConversationForLangChainOpenAiMultiAgentProvider() throws Exception {
    PluginDataHandler changeDataHandler = Mockito.mock(PluginDataHandler.class);
    String conversationKey =
        OpenAiConversation.getMultiAgentConversationKey(ReviewAssistantStages.REVIEW_CODE);
    when(changeDataHandler.getValue(conversationKey)).thenReturn("conv_review_code");
    PluginDataHandlerProvider pluginDataHandlerProvider = Mockito.mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setForcedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStages.REVIEW_CODE);

    String conversationId =
        resolveConversationId(
            new LangChainClient(
                Mockito.mock(Configuration.class), null, null, null, pluginDataHandlerProvider),
            AiProviderType.OPENAI,
            changeSetData);

    assertEquals("conv_review_code", conversationId);
  }

  @Test
  public void omitsConversationForNonOpenAiLangChainProvider() throws Exception {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);

    String conversationId =
        resolveConversationId(
            new LangChainClient(Mockito.mock(Configuration.class), null, null, null),
            AiProviderType.OLLAMA,
            changeSetData);

    assertEquals(null, conversationId);
  }

  private String resolveConversationId(
      LangChainClient client, AiProviderType providerType, ChangeSetData changeSetData)
      throws Exception {
    Method method =
        LangChainClient.class.getDeclaredMethod(
            "resolveConversationId", AiProviderType.class, ChangeSetData.class);
    method.setAccessible(true);
    return (String) method.invoke(client, providerType, changeSetData);
  }
}
