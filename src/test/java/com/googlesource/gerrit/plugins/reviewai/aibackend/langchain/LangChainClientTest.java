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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.messages.LangChainChatMessages;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.mockito.Mockito;

public class LangChainClientTest {
  private static final String AI_RESPONSE_CONTENT_TRAILING_WHITESPACE_RESOURCE =
      "__files/langchain/aiResponseContentWithTrailingWhitespace.json";
  private static final String AI_RESPONSE_CONTENT_DUPLICATED_REPLY_RESOURCE =
      "__files/langchain/aiResponseContentWithDuplicatedReply.json";
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
    assertTrue(replyItemSchema.properties().containsKey("source_agent"));
    assertTrue(replyItemSchema.properties().containsKey("repetition_reply_id"));
    assertTrue(replyItemSchema.properties().containsKey("duplicated"));
    assertTrue(replyItemSchema.properties().containsKey("duplicated_reason"));
  }

  @Test
  public void concernReviewResponseFormatExcludesClientAssignedSkippedStatus() throws Exception {
    LangChainClient client = new LangChainClient(null, null, null, null);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_CONCERNS);

    ResponseFormat responseFormat =
        getToolExecutorStructuredResponseFormat(getToolExecutor(client, changeSetData));

    JsonObjectSchema root = (JsonObjectSchema) responseFormat.jsonSchema().rootElement();
    JsonArraySchema concerns = (JsonArraySchema) root.properties().get("concerns");
    JsonObjectSchema concern = (JsonObjectSchema) concerns.items();
    JsonEnumSchema status = (JsonEnumSchema) concern.properties().get("status");
    assertEquals(
        List.of("PRESENT", "FIXED", "UNCERTAIN", "DISMISSED"), status.enumValues());
  }

  @Test
  public void shouldLoadSpecializedStructuredResponseFormatForFindings()
      throws Exception {
    LangChainClient client = new LangChainClient(null, null, null, null);

    ResponseFormat responseFormat = getSpecializedRepliesResponseFormat(client);

    assertNotNull("Specialized response format should be loaded", responseFormat);
    assertEquals(ResponseFormatType.JSON, responseFormat.type());

    JsonSchema jsonSchema = responseFormat.jsonSchema();
    assertNotNull(jsonSchema);
    assertEquals("format_specialized_replies", jsonSchema.name());
    assertTrue(jsonSchema.rootElement() instanceof JsonObjectSchema);

    JsonObjectSchema root = (JsonObjectSchema) jsonSchema.rootElement();
    assertTrue(root.properties().containsKey("concerns"));
    assertTrue(root.properties().containsKey("dismissed_concerns"));
    assertFalse(root.properties().containsKey("replies"));
    assertFalse(root.properties().containsKey("changeId"));

    JsonArraySchema concernsSchema = (JsonArraySchema) root.properties().get("concerns");
    assertNotNull(concernsSchema.items());
    assertTrue(concernsSchema.items() instanceof JsonObjectSchema);
    JsonObjectSchema concernItemSchema = (JsonObjectSchema) concernsSchema.items();
    assertTrue(concernItemSchema.properties().containsKey("type"));
    assertTrue(concernItemSchema.properties().containsKey("description"));
    assertTrue(concernItemSchema.properties().containsKey("reasoning"));
    assertTrue(concernItemSchema.properties().containsKey("preexisting"));
    assertTrue(concernItemSchema.properties().containsKey("locations"));
    assertFalse(concernItemSchema.properties().containsKey("reply"));
    assertFalse(concernItemSchema.properties().containsKey("score"));
    assertFalse(concernItemSchema.properties().containsKey("relevance"));
  }

  @Test
  public void usesSpecializedExecutorForSpecializedAgentRequests() throws Exception {
    LangChainClient client = new LangChainClient(null, null, null, null);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setSpecializedAgentReview(true);

    assertSame(getSpecializedRepliesToolExecutor(client), getToolExecutor(client, changeSetData));
  }

  @Test
  public void usesDedicatedExecutorForEachSpecializedCollector() throws Exception {
    LangChainClient client = new LangChainClient(null, null, null, null);

    assertCollectorExecutor(
        client,
        ReviewAssistantStage.REVIEW_SPECIALIZED_CONSOLIDATION,
        "specializedConsolidationToolExecutor");
    assertCollectorExecutor(
        client,
        ReviewAssistantStage.REVIEW_SPECIALIZED_HISTORICAL_REPETITION,
        "specializedHistoricalRepetitionToolExecutor");
    assertCollectorExecutor(
        client,
        ReviewAssistantStage.REVIEW_SPECIALIZED_CONFLICT_RESOLUTION,
        "specializedConflictResolutionToolExecutor");
    assertCollectorExecutor(
        client,
        ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION,
        "specializedVerificationToolExecutor");
  }

  @Test
  public void shouldLoadSpecializedTriageResponseFormatWithDirectAgents() throws Exception {
    LangChainClient client = new LangChainClient(null, null, null, null);

    ResponseFormat responseFormat = getSpecializedTriageResponseFormat(client);

    assertNotNull("Specialized triage response format should be loaded", responseFormat);
    assertEquals(ResponseFormatType.JSON, responseFormat.type());

    JsonSchema jsonSchema = responseFormat.jsonSchema();
    assertNotNull(jsonSchema);
    assertEquals("format_specialized_triage", jsonSchema.name());
    assertTrue(jsonSchema.rootElement() instanceof JsonObjectSchema);

    JsonObjectSchema root = (JsonObjectSchema) jsonSchema.rootElement();
    assertTrue(root.properties().containsKey("agents"));
    assertTrue(root.properties().containsKey("consolidation_context"));
    assertFalse(root.properties().containsKey("replies"));
    assertFalse(root.properties().containsKey("changeId"));

    JsonArraySchema agentsSchema = (JsonArraySchema) root.properties().get("agents");
    assertNotNull(agentsSchema.items());
    assertTrue(agentsSchema.items() instanceof JsonObjectSchema);
    JsonObjectSchema agentItemSchema = (JsonObjectSchema) agentsSchema.items();
    assertTrue(agentItemSchema.properties().containsKey("agent"));
    assertTrue(agentItemSchema.properties().containsKey("enabled"));
    assertTrue(agentItemSchema.properties().containsKey("reason"));
    assertFalse(agentItemSchema.properties().containsKey("patchset_context"));
    assertTrue(agentItemSchema.properties().containsKey("history_context"));
    assertTrue(agentItemSchema.properties().containsKey("custom_instructions"));
  }

  @Test
  public void usesSpecializedTriageExecutorForTriageRequests() throws Exception {
    LangChainClient client = new LangChainClient(null, null, null, null);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_TRIAGE);

    assertSame(getSpecializedTriageToolExecutor(client), getToolExecutor(client, changeSetData));
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
    assertEquals("CORRECTNESS", responseContent.getReplies().get(0).getSourceAgent());
  }

  @Test
  public void parsesJsonResponseWithDuplicatedReply() throws Exception {
    String responseText = readTestResource(AI_RESPONSE_CONTENT_DUPLICATED_REPLY_RESOURCE);
    TestableLangChainClient client = new TestableLangChainClient();

    AiResponseContent responseContent = client.parseResponseContent(responseText);

    AiReplyItem reply = responseContent.getReplies().getFirst();
    assertTrue(reply.isDuplicated());
    assertFalse(reply.isRepeated());
    assertEquals("Same issue already reported by CORRECTNESS.", reply.getDuplicatedReason());
  }

  @Test
  public void resendsRequestWhenMockReturnsProviderFallbackDirective() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    AiModelRoute defaultRoute = new AiModelRoute(AiProviderType.OPENAI, "gpt-5.4");
    when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.NONE);
    when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
    when(config.resolveMockAiFallbackRoute("FORWARD")).thenReturn(Optional.of(defaultRoute));
    FallbackTestLangChainClient client = new FallbackTestLangChainClient(config);

    AiResponseContent responseContent = client.request();

    assertEquals("real response", responseContent.getMessageContent());
    assertEquals(2, client.requestCount);
    assertEquals("OpenAI/gpt-5.4", client.fallbackRoute.modelRoute());
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
    assertEquals(true, getToolExecutorRequireInitialToolUse(client));
  }

  @Test
  public void openAiZdrRequiresInitialOnDemandToolUseWithResponses() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.ON_DEMAND);
    when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
    when(config.getAiProviderZdr()).thenReturn(true);

    LangChainClient client = new LangChainClient(config, null, null, null);

    assertEquals(true, getToolExecutorRequireInitialToolUse(client));
  }

  @Test
  public void openAiZdrUsesLocalMemoryForStatelessResponses() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiProviderZdr()).thenReturn(true);

    TestableLangChainClient client = new TestableLangChainClient(config);

    assertFalse(client.useOpenAiConversation(AiProviderType.OPENAI));
  }

  @Test
  public void openAiNonZdrUsesServerConversationInsteadOfLocalMemory() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiProviderZdr()).thenReturn(false);

    TestableLangChainClient client = new TestableLangChainClient(config);

    assertTrue(client.useOpenAiConversation(AiProviderType.OPENAI));
  }

  @Test
  public void usesJsonObjectResponseFormatWithoutSchemaForDeepSeek() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.ON_DEMAND);
    when(config.getAiProviderType()).thenReturn(AiProviderType.DEEPSEEK);

    LangChainClient client = new LangChainClient(config, null, null, null);

    ResponseFormat responseFormat = getToolExecutorStructuredResponseFormat(client);
    assertNotNull(responseFormat);
    assertEquals(ResponseFormatType.JSON, responseFormat.type());
    assertNull(responseFormat.jsonSchema());
    assertTrue(getToolExecutorOnDemandTools(client).size() > 0);
  }

  @Test
  public void resolvesOpenAiConversationForLangChainOpenAiProvider() throws Exception {
    PluginDataHandler changeDataHandler = Mockito.mock(PluginDataHandler.class);
    when(changeDataHandler.getValue(OpenAiConversation.KEY_CONVERSATION_ID))
        .thenReturn("conv_langchain_openai");
    PluginDataHandlerProvider pluginDataHandlerProvider = Mockito.mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    ChangeSetData changeSetData = new ChangeSetData(1);
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
  public void skipsOpenAiConversationWhenAiProviderZdrIsEnabled() throws Exception {
    PluginDataHandler changeDataHandler = Mockito.mock(PluginDataHandler.class);
    when(changeDataHandler.getValue(OpenAiConversation.KEY_CONVERSATION_ID))
        .thenReturn("conv_langchain_openai");
    PluginDataHandlerProvider pluginDataHandlerProvider = Mockito.mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiProviderZdr()).thenReturn(true);

    String conversationId =
        resolveConversationId(
            new LangChainClient(config, null, null, null, pluginDataHandlerProvider),
            AiProviderType.OPENAI,
            new ChangeSetData(1));

    assertEquals(null, conversationId);
    verify(changeDataHandler, never()).getValue(OpenAiConversation.KEY_CONVERSATION_ID);
  }

  @Test
  public void forgetThreadClearsExistingOpenAiConversationAndCreatesFreshConversation()
      throws Exception {
    FakeOpenAiConversation conversation =
        new FakeOpenAiConversation("conv_fresh_langchain_openai", true);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.addParsedCommand("forget_thread", Map.of());

    String conversationId =
        resolveConversationId(
            new OpenAiConversationTestLangChainClient(conversation),
            AiProviderType.OPENAI,
            changeSetData);

    assertEquals("conv_fresh_langchain_openai", conversationId);
    assertTrue(conversation.clearCurrentConversationCalled);
    assertFalse(conversation.hasExistingConversationCalled);
  }

  @Test
  public void timeoutClearsCurrentOpenAiConversation() {
    FakeOpenAiConversation conversation = new FakeOpenAiConversation("conv_timed_out", true);
    OpenAiConversationTestLangChainClient client =
        new OpenAiConversationTestLangChainClient(conversation);

    client.clearTimedOutConversation(
        AiProviderType.OPENAI,
        new ChangeSetData(1),
        new RuntimeException(new SocketTimeoutException()));

    assertTrue(conversation.clearCurrentConversationCalled);
  }

  @Test
  public void resolvesOpenAiConversationForNormalFollowUpMessage() throws Exception {
    PluginDataHandler changeDataHandler = Mockito.mock(PluginDataHandler.class);
    when(changeDataHandler.getValue(OpenAiConversation.KEY_CONVERSATION_ID))
        .thenReturn("conv_follow_up");
    PluginDataHandlerProvider pluginDataHandlerProvider = Mockito.mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    ChangeSetData changeSetData = new ChangeSetData(1);
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
  public void resolvesDedicatedOpenAiConversationForCommentMessage() throws Exception {
    PluginDataHandler changeDataHandler = Mockito.mock(PluginDataHandler.class);
    when(changeDataHandler.getValue(OpenAiConversation.getMessagesConversationKey()))
        .thenReturn("conv_message");
    PluginDataHandlerProvider pluginDataHandlerProvider = Mockito.mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    ChangeSetData changeSetData = new ChangeSetData(1);
    GerritChange change = Mockito.mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);

    String conversationId =
        resolveConversationId(
            new LangChainClient(
                Mockito.mock(Configuration.class), null, null, null, pluginDataHandlerProvider),
            AiProviderType.OPENAI,
            changeSetData,
            change);

    assertEquals("conv_message", conversationId);
    verify(changeDataHandler, never()).getValue(OpenAiConversation.KEY_CONVERSATION_ID);
  }

  @Test
  public void resolvesStageConversationForLangChainOpenAiMultiAgentProvider() throws Exception {
    PluginDataHandler changeDataHandler = Mockito.mock(PluginDataHandler.class);
    String conversationKey =
        OpenAiConversation.getMultiAgentConversationKey(ReviewAssistantStage.REVIEW_CODE);
    when(changeDataHandler.getValue(conversationKey)).thenReturn("conv_review_code");
    PluginDataHandlerProvider pluginDataHandlerProvider = Mockito.mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_CODE);

    String conversationId =
        resolveConversationId(
            new LangChainClient(
                Mockito.mock(Configuration.class), null, null, null, pluginDataHandlerProvider),
            AiProviderType.OPENAI,
            changeSetData);

    assertEquals("conv_review_code", conversationId);
  }

  @Test
  public void resolvesSeparateOpenAiConversationForSuffixedStage() throws Exception {
    PluginDataHandler changeDataHandler = Mockito.mock(PluginDataHandler.class);
    String conversationKey =
        OpenAiConversation.getMultiAgentConversationKey(
            ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION, "reviewai-topic-change-1");
    when(changeDataHandler.getValue(conversationKey)).thenReturn("conv_verification_topic_1");
    PluginDataHandlerProvider pluginDataHandlerProvider = Mockito.mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION);
    changeSetData.setReviewAssistantStageConversationSuffix("reviewai-topic-change-1");

    String conversationId =
        resolveConversationId(
            new LangChainClient(
                Mockito.mock(Configuration.class), null, null, null, pluginDataHandlerProvider),
            AiProviderType.OPENAI,
            changeSetData);

    assertEquals("conv_verification_topic_1", conversationId);
    verify(changeDataHandler, never())
        .getValue(
            OpenAiConversation.getMultiAgentConversationKey(
                ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION));
  }

  @Test
  public void resolvesSeparateOpenAiConversationForEachSpecializedAgent() throws Exception {
    PluginDataHandler changeDataHandler = Mockito.mock(PluginDataHandler.class);
    PluginDataHandlerProvider pluginDataHandlerProvider = Mockito.mock(PluginDataHandlerProvider.class);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    for (String agent : List.of("CORRECTNESS", "TESTABILITY", "CUSTOM_AGENT")) {
      String conversationKey = OpenAiConversation.getSpecializedAgentConversationKey(agent);
      when(changeDataHandler.getValue(conversationKey)).thenReturn("conv_" + agent);

      ChangeSetData changeSetData = new ChangeSetData(1);
      changeSetData.setForcedReview(true);
      changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT);
      changeSetData.setSpecializedAgentName(agent);

      String conversationId =
          resolveConversationId(
              new LangChainClient(
                  Mockito.mock(Configuration.class), null, null, null, pluginDataHandlerProvider),
              AiProviderType.OPENAI,
              changeSetData);

      assertEquals("conv_" + agent, conversationId);
    }
  }

  @Test
  public void omitsConversationForNonOpenAiLangChainProvider() throws Exception {
    ChangeSetData changeSetData = new ChangeSetData(1);

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
    ChangeSetData changeSetData = new ChangeSetData(1);
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
    ChangeSetData changeSetData = new ChangeSetData(1);
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
  public void openAiZdrKeepsPatchEvenWhenConversationExists() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    GerritChange change = Mockito.mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiProviderZdr()).thenReturn(true);
    TestableLangChainClient client = new TestableLangChainClient(config);

    assertEquals(
        false,
        client.omitRequestContext(
            AiProviderType.OPENAI, true, changeSetData, change));
  }

  @Test
  public void automaticReviewKeepsPatchEvenWhenOpenAiConversationExists() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    GerritChange change = Mockito.mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(false);
    TestableLangChainClient client = new TestableLangChainClient();

    assertEquals(
        false,
        client.omitRequestContext(
            AiProviderType.OPENAI, true, changeSetData, change));
  }

  @Test
  public void forgetThreadSkipsInitialHistory() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.addParsedCommand("forget_thread", Map.of());

    assertFalse(new TestableLangChainClient().includeInitialHistory(changeSetData));
  }

  @Test
  public void staleStoredSystemInstructionsResetPersistentMemory() {
    TestableLangChainClient client = new TestableLangChainClient();
    ChatMemory memory = chatMemory("review");
    memory.add(LangChainChatMessages.systemMessage("old instructions"));
    memory.add(LangChainChatMessages.userMessage("old request"));

    boolean hasStoredMemory = client.prepareMemory(memory, false, "new instructions");

    assertFalse(hasStoredMemory);
    assertEquals(1, memory.messages().size());
    assertEquals("new instructions", LangChainChatMessages.content(memory.messages().getFirst()));
  }

  @Test
  public void currentStoredSystemInstructionsKeepPersistentMemory() {
    TestableLangChainClient client = new TestableLangChainClient();
    ChatMemory memory = chatMemory("review");
    memory.add(LangChainChatMessages.systemMessage("current instructions"));
    memory.add(LangChainChatMessages.userMessage("previous request"));

    boolean hasStoredMemory = client.prepareMemory(memory, false, "current instructions");

    assertTrue(hasStoredMemory);
    assertEquals(2, memory.messages().size());
    assertEquals("previous request", LangChainChatMessages.content(memory.messages().get(1)));
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

  private String resolveConversationId(
      LangChainClient client,
      AiProviderType providerType,
      ChangeSetData changeSetData,
      GerritChange change)
      throws Exception {
    Method method =
        LangChainClient.class.getDeclaredMethod(
            "resolveConversation", AiProviderType.class, ChangeSetData.class, GerritChange.class);
    method.setAccessible(true);
    Object conversationResolution = method.invoke(client, providerType, changeSetData, change);
    Method conversationIdMethod = conversationResolution.getClass().getDeclaredMethod("conversationId");
    conversationIdMethod.setAccessible(true);
    return (String) conversationIdMethod.invoke(conversationResolution);
  }

  private ResponseFormat getToolExecutorStructuredResponseFormat(LangChainClient client)
      throws Exception {
    return getToolExecutorStructuredResponseFormat(getToolExecutor(client));
  }

  private ResponseFormat getToolExecutorStructuredResponseFormat(Object executor)
      throws Exception {
    Field responseFormatField = executor.getClass().getDeclaredField("structuredResponseFormat");
    responseFormatField.setAccessible(true);
    return (ResponseFormat) responseFormatField.get(executor);
  }

  private java.util.List<?> getToolExecutorOnDemandTools(LangChainClient client) throws Exception {
    Object executor = getToolExecutor(client);
    Field toolsField = executor.getClass().getDeclaredField("onDemandTools");
    toolsField.setAccessible(true);
    return (java.util.List<?>) toolsField.get(executor);
  }

  private boolean getToolExecutorRequireInitialToolUse(LangChainClient client) throws Exception {
    Object executor = getToolExecutor(client);
    Field field = executor.getClass().getDeclaredField("requireInitialToolUse");
    field.setAccessible(true);
    return (boolean) field.get(executor);
  }

  private Object getToolExecutor(LangChainClient client) throws Exception {
    Field executorField = LangChainClient.class.getDeclaredField("toolExecutor");
    executorField.setAccessible(true);
    return executorField.get(client);
  }

  private Object getToolExecutor(LangChainClient client, ChangeSetData changeSetData)
      throws Exception {
    Method method = LangChainClient.class.getDeclaredMethod("getToolExecutor", ChangeSetData.class);
    method.setAccessible(true);
    return method.invoke(client, changeSetData);
  }

  private void assertCollectorExecutor(
      LangChainClient client, ReviewAssistantStage stage, String executorFieldName)
      throws Exception {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setReviewAssistantStage(stage);
    Field field = LangChainClient.class.getDeclaredField(executorFieldName);
    field.setAccessible(true);

    assertSame(field.get(client), getToolExecutor(client, changeSetData));
  }

  private ResponseFormat getSpecializedRepliesResponseFormat(LangChainClient client)
      throws Exception {
    Field field = LangChainClient.class.getDeclaredField("specializedRepliesResponseFormat");
    field.setAccessible(true);
    return (ResponseFormat) field.get(client);
  }

  private Object getSpecializedRepliesToolExecutor(LangChainClient client) throws Exception {
    Field executorField =
        LangChainClient.class.getDeclaredField("specializedRepliesToolExecutor");
    executorField.setAccessible(true);
    return executorField.get(client);
  }

  private ResponseFormat getSpecializedTriageResponseFormat(LangChainClient client)
      throws Exception {
    Field field = LangChainClient.class.getDeclaredField("specializedTriageResponseFormat");
    field.setAccessible(true);
    return (ResponseFormat) field.get(client);
  }

  private Object getSpecializedTriageToolExecutor(LangChainClient client) throws Exception {
    Field executorField =
        LangChainClient.class.getDeclaredField("specializedTriageToolExecutor");
    executorField.setAccessible(true);
    return executorField.get(client);
  }

  private String readTestResource(String resourceName) throws Exception {
    try (InputStream resource = getClass().getClassLoader().getResourceAsStream(resourceName)) {
      assertNotNull("Test resource should exist: " + resourceName, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static class OpenAiConversationTestLangChainClient extends LangChainClient {
    private final OpenAiConversation conversation;

    private OpenAiConversationTestLangChainClient(OpenAiConversation conversation) {
      super(
          Mockito.mock(Configuration.class),
          null,
          null,
          null,
          Mockito.mock(PluginDataHandlerProvider.class));
      this.conversation = conversation;
    }

    @Override
    protected OpenAiConversation openAiConversation(
        ChangeSetData changeSetData, GerritChange change) {
      return conversation;
    }

    private void clearTimedOutConversation(
        AiProviderType providerType, ChangeSetData changeSetData, Throwable failure) {
      clearTimedOutOpenAiConversation(providerType, changeSetData, null, failure);
    }
  }

  private static class FakeOpenAiConversation extends OpenAiConversation {
    private final String conversationId;
    private boolean existingConversation;
    private boolean clearCurrentConversationCalled;
    private boolean hasExistingConversationCalled;

    private FakeOpenAiConversation(String conversationId, boolean existingConversation) {
      super(Mockito.mock(Configuration.class), pluginDataHandlerProvider());
      this.conversationId = conversationId;
      this.existingConversation = existingConversation;
    }

    @Override
    public void clearCurrentConversation() {
      clearCurrentConversationCalled = true;
      existingConversation = false;
    }

    @Override
    public boolean hasExistingConversation() {
      hasExistingConversationCalled = true;
      return existingConversation;
    }

    @Override
    public String resolveConversationId() {
      return conversationId;
    }

    private static PluginDataHandlerProvider pluginDataHandlerProvider() {
      PluginDataHandlerProvider pluginDataHandlerProvider =
          Mockito.mock(PluginDataHandlerProvider.class);
      when(pluginDataHandlerProvider.getChangeScope())
          .thenReturn(Mockito.mock(PluginDataHandler.class));
      return pluginDataHandlerProvider;
    }
  }

  private static class TestableLangChainClient extends LangChainClient {
    private TestableLangChainClient() {
      super(null, null, null, null);
    }

    private TestableLangChainClient(Configuration config) {
      super(config, null, null, null);
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

    private boolean includeInitialHistory(ChangeSetData changeSetData) {
      return shouldIncludeInitialHistory(changeSetData);
    }

    private boolean useOpenAiConversation(AiProviderType providerType) {
      return shouldUseOpenAiConversation(providerType);
    }

    private boolean prepareMemory(
        ChatMemory memory, boolean useOpenAiResponses, String systemInstructions) {
      return prepareMemoryForRequest(memory, useOpenAiResponses, systemInstructions);
    }
  }

  private static ChatMemory chatMemory(String id) {
    return TokenWindowChatMemory.builder()
        .id(id)
        .maxTokens(1000, new TestTokenCountEstimator())
        .build();
  }

  private static class TestTokenCountEstimator implements TokenCountEstimator {
    @Override
    public int estimateTokenCountInText(String text) {
      return text == null ? 0 : Math.max(1, text.length() / 4);
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
      return estimateTokenCountInText(LangChainChatMessages.content(message));
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
      int count = 0;
      for (ChatMessage message : messages) {
        count += estimateTokenCountInMessage(message);
      }
      return count;
    }
  }

  private static class FallbackTestLangChainClient extends LangChainClient {
    private int requestCount;
    private AiModelRoute fallbackRoute;

    private FallbackTestLangChainClient(Configuration config) {
      super(config, null, null, null);
    }

    private AiResponseContent request() throws Exception {
      return askSingleRequest(null, null, "").getResponseContent();
    }

    @Override
    protected RawReviewRequestResult askSingleRawRequest(
      ChangeSetData changeSetData, GerritChange change, String patchSet) {
      requestCount++;
      return rawReviewRequestResult("FORWARD", "mock request");
    }

    @Override
    protected RawReviewRequestResult askSingleRawRequest(
        ChangeSetData changeSetData,
        GerritChange change,
        String patchSet,
        AiModelRoute aiModelRouteOverride) {
      requestCount++;
      fallbackRoute = aiModelRouteOverride;
      return rawReviewRequestResult("real response", "fallback request");
    }
  }
}
