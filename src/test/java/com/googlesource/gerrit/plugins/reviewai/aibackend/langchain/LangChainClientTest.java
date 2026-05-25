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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.openai.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import org.mockito.Mockito;

public class LangChainClientTest {
  private static final String AI_RESPONSE_CONTENT_TRAILING_WHITESPACE_RESOURCE =
      "__files/langchain/aiResponseContentWithTrailingWhitespace.json";
  private static final String OPENAI_PROMPT_TAG_REQUESTS_RESOURCE =
      "__files/openai/openAiPromptTagRequests.json";
  private static final String GERRIT_FORMATTED_PATCH_RESOURCE =
      "__files/openai/gerritFormattedPatch.txt";

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
  public void parsesJsonResponseWithTrailingWhitespace() throws Exception {
    String responseText = readTestResource(AI_RESPONSE_CONTENT_TRAILING_WHITESPACE_RESOURCE);
    TestableLangChainClient client = new TestableLangChainClient();

    AiResponseContent responseContent = client.parseResponseContent(responseText);

    assertEquals("myChangeId", responseContent.getChangeId());
    assertNotNull(responseContent.getReplies());
    assertEquals(1, responseContent.getReplies().size());
    assertEquals(
        "Trailing whitespace should not prevent parsing.",
        responseContent.getReplies().get(0).getReply());
  }

  @Test
  public void omitsStructuredResponseFormatForGeminiOnDemandTools() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.ON_DEMAND);
    when(config.getAiProviderType()).thenReturn(AiProviderType.GEMINI);

    LangChainClient client = new LangChainClient(config, null, null, null);

    assertNull(getToolExecutorStructuredResponseFormat(client));
  }

  @Test
  public void keepsStructuredResponseFormatForOpenAiOnDemandTools() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.ON_DEMAND);
    when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);

    LangChainClient client = new LangChainClient(config, null, null, null);

    assertNotNull(getToolExecutorStructuredResponseFormat(client));
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
  public void resolvesOpenAiConversationForNormalFollowUpMessage() throws Exception {
    PluginDataHandler changeDataHandler = Mockito.mock(PluginDataHandler.class);
    when(changeDataHandler.getValue(OpenAiConversation.KEY_CONVERSATION_ID))
        .thenReturn("conv_follow_up");
    PluginDataHandlerProvider pluginDataHandlerProvider = Mockito.mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setReviewAssistantStage(null);

    String conversationId =
        resolveConversationId(
            new LangChainClient(
                Mockito.mock(Configuration.class), null, null, null, pluginDataHandlerProvider),
            AiProviderType.OPENAI,
            changeSetData);

    assertEquals("conv_follow_up", conversationId);
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

  @Test
  public void contextlessExistingOpenAiConversationUsesOnlyRequestData() throws Exception {
    String requestData = readTestResource(OPENAI_PROMPT_TAG_REQUESTS_RESOURCE);
    String patchSet = readTestResource(GERRIT_FORMATTED_PATCH_RESOURCE);
    IAiPrompt prompt = Mockito.mock(IAiPrompt.class);
    when(prompt.getAiRequestDataPrompt()).thenReturn(requestData);
    TestableLangChainClient client = new TestableLangChainClient();

    String userMessage = client.userMessageForRequest(prompt, patchSet, true);

    assertEquals(requestData, userMessage);
    verify(prompt, never()).getDefaultAiThreadReviewMessage(patchSet);
  }

  @Test
  public void contextlessExistingOpenAiConversationDropsPatchWhenRequestDataIsAbsent()
      throws Exception {
    String requestData = readTestResource(OPENAI_PROMPT_TAG_REQUESTS_RESOURCE);
    String patchSet = readTestResource(GERRIT_FORMATTED_PATCH_RESOURCE);
    IAiPrompt prompt = Mockito.mock(IAiPrompt.class);
    when(prompt.getAiRequestDataPrompt()).thenReturn(null);
    when(prompt.getDefaultAiThreadReviewMessage("")).thenReturn(requestData);
    TestableLangChainClient client = new TestableLangChainClient();

    String userMessage = client.userMessageForRequest(prompt, patchSet, true);

    assertEquals(requestData, userMessage);
    verify(prompt).getDefaultAiThreadReviewMessage("");
    verify(prompt, never()).getDefaultAiThreadReviewMessage(patchSet);
  }

  @Test
  public void fullLangChainRequestKeepsPatchWhenOpenAiConversationIsNew() throws Exception {
    String requestData = readTestResource(OPENAI_PROMPT_TAG_REQUESTS_RESOURCE);
    String patchSet = readTestResource(GERRIT_FORMATTED_PATCH_RESOURCE);
    IAiPrompt prompt = Mockito.mock(IAiPrompt.class);
    when(prompt.getDefaultAiThreadReviewMessage(patchSet)).thenReturn(requestData);
    TestableLangChainClient client = new TestableLangChainClient();

    String userMessage = client.userMessageForRequest(prompt, patchSet, false);

    assertEquals(requestData, userMessage);
    verify(prompt).getDefaultAiThreadReviewMessage(patchSet);
    verify(prompt, never()).getDefaultAiThreadReviewMessage("");
  }

  @Test
  public void omitsRequestContextOnlyForNormalCommentFollowUps() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    GerritChange change = Mockito.mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    TestableLangChainClient client = new TestableLangChainClient();

    assertEquals(
        true,
        client.omitRequestContext(
            AiProviderType.OPENAI, true, changeSetData, change));
  }

  @Test
  public void forcedReviewKeepsPatchEvenWhenOpenAiConversationExists() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setForcedReview(true);
    GerritChange change = Mockito.mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    TestableLangChainClient client = new TestableLangChainClient();

    assertEquals(
        false,
        client.omitRequestContext(
            AiProviderType.OPENAI, true, changeSetData, change));
  }

  @Test
  public void automaticReviewKeepsPatchEvenWhenOpenAiConversationExists() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    GerritChange change = Mockito.mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(false);
    TestableLangChainClient client = new TestableLangChainClient();

    assertEquals(
        false,
        client.omitRequestContext(
            AiProviderType.OPENAI, true, changeSetData, change));
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

  private ResponseFormat getToolExecutorStructuredResponseFormat(LangChainClient client)
      throws Exception {
    Field executorField = LangChainClient.class.getDeclaredField("toolExecutor");
    executorField.setAccessible(true);
    Object executor = executorField.get(client);
    Field responseFormatField = executor.getClass().getDeclaredField("structuredResponseFormat");
    responseFormatField.setAccessible(true);
    return (ResponseFormat) responseFormatField.get(executor);
  }

  private String readTestResource(String resourceName) throws Exception {
    URL resource = getClass().getClassLoader().getResource(resourceName);
    assertNotNull("Test resource should exist: " + resourceName, resource);
    return Files.readString(Paths.get(resource.toURI()));
  }

  private static class TestableLangChainClient extends LangChainClient {
    private TestableLangChainClient() {
      super(null, null, null, null);
    }

    private AiResponseContent parseResponseContent(String responseText) {
      return toResponseContent(responseText);
    }

    private String userMessageForRequest(
        IAiPrompt prompt, String patchSet, boolean omitContext) {
      return getUserMessageForRequest(prompt, patchSet, omitContext);
    }

    private boolean omitRequestContext(
        AiProviderType providerType,
        boolean existingConversation,
        ChangeSetData changeSetData,
        GerritChange change) {
      return shouldOmitRequestContext(
          providerType,
          existingConversation,
          changeSetData,
          change);
    }
  }
}
