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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.google.common.net.HttpHeaders;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.OpenAiUriResourceLocator;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponsesResponse;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.openai.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;

import static com.googlesource.gerrit.plugins.reviewai.listener.EventHandlerTask.SupportedEvents;
import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_PATCH_SET_FILENAME;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.TemplateUtils.renderTemplate;
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class OpenAiReviewMultiAgentModeTest extends OpenAiReviewTestBase {
  private static final String SECOND_CALL = "second-call";
  private static final String SECOND_CONVERSATION = "second-conversation";
  private static final String CONVERSATION_SCENARIO = "Create-Conversation Scenario";
  private static final String REVIEW_CODE_CONVERSATION_ID = "conv_REVIEW_CODE";
  private static final String REVIEW_COMMIT_MESSAGE_CONVERSATION_ID =
      "conv_REVIEW_COMMIT_MESSAGE";

  public OpenAiReviewMultiAgentModeTest() {
    MockitoAnnotations.openMocks(this);
  }

  @Override
  protected void initGlobalAndProjectConfig() {
    super.initGlobalAndProjectConfig();

    when(globalConfig.getBoolean(Mockito.eq("multiAgentMode"), Mockito.anyBoolean()))
        .thenReturn(true);
  }

  @Override
  protected void setupMockRequests() throws RestApiException {
    super.setupMockRequests();

    setupMockRequestCreateResponseFromBody(
        filterOutSubsetResponse(1, 2), Scenario.STARTED, SECOND_CALL);
    setupMockRequestCreateResponseFromBody(filterOutSubsetResponse(0, 1), SECOND_CALL, null);
    setupMockRequestCreateConversation(
        REVIEW_CODE_CONVERSATION_ID, Scenario.STARTED, SECOND_CONVERSATION);
    setupMockRequestCreateConversation(
        REVIEW_COMMIT_MESSAGE_CONVERSATION_ID, SECOND_CONVERSATION, null);
  }

  private String filterOutSubsetResponse(int from, int to) {
    OpenAiResponsesResponse response =
        GsonUtils.jsonToClass(
            readTestFile(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json"),
            OpenAiResponsesResponse.class);
    response.getOutput().subList(from, to).clear();
    return getGson().toJson(response);
  }

  private void setupCommandComment(String command) throws RestApiException {
    String commentJson =
        renderTemplate(
            readTestFile("__files/commands/commandCommentTemplate.json"),
            Map.of("command", command));
    Map<String, List<CommentInfo>> comments = readContentToType(commentJson, COMMENTS_GERRIT_TYPE);
    mockGerritChangeCommentsApiCall(comments);
  }

  private void setupMockRequestCreateConversation(
      String conversationId, String fromState, String toState) {
    MappingBuilder mappingBuilder =
        WireMock.post(WireMock.urlEqualTo(OpenAiUriResourceLocator.conversationsUri()))
            .atPriority(1);
    if (fromState != null) {
      mappingBuilder =
          mappingBuilder
              .inScenario(CONVERSATION_SCENARIO)
              .whenScenarioStateIs(fromState)
              .willSetStateTo(toState);
    }
    WireMock.stubFor(
        mappingBuilder.willReturn(
            WireMock.aResponse()
                .withStatus(200)
                .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                .withBody("{\"id\": \"" + conversationId + "\"}")));
  }

  @Test
  public void reviewCodeInstructionsExcludeCommitMessageReviewPrompts() {
    IAiPrompt reviewCodePrompt = getAiPrompt(ReviewAssistantStages.REVIEW_CODE);

    String instructions = reviewCodePrompt.getDefaultAiAssistantInstructions();

    Assert.assertFalse(instructions.contains("You MUST review the commit message"));
    Assert.assertFalse(
        instructions.contains(AiPrompt.DEFAULT_AI_REVIEW_PROMPT_INSTRUCTIONS_COMMIT_MESSAGES));
  }

  @Test
  public void reviewCommitMessageInstructionsIncludeCommitMessageReviewPrompts() {
    IAiPrompt commitMessagePrompt = getAiPrompt(ReviewAssistantStages.REVIEW_COMMIT_MESSAGE);

    String instructions = commitMessagePrompt.getDefaultAiAssistantInstructions();

    Assert.assertTrue(
        instructions.startsWith(
            sectionHeader(AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE)
                + "You are a Git commit message expert, tasked with improving the quality and clarity of commit messages."));
    Assert.assertTrue(instructions.contains("Remote Gerrit improve commit message prompt."));
    Assert.assertTrue(
        instructions.contains(
            "Return the feedback using this plugin's mandatory JSON response format"));
    Assert.assertTrue(instructions.contains("You MUST review the commit message"));
    Assert.assertTrue(
        instructions.contains(AiPrompt.DEFAULT_AI_REVIEW_PROMPT_INSTRUCTIONS_COMMIT_MESSAGES));
    Assert.assertTrue(
        instructions.contains(
            sectionHeader(AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RESPONSE_FORMAT)));
    Assert.assertFalse(instructions.contains("Remote Gerrit review prompt."));
    Assert.assertFalse(instructions.contains("{{patch}}"));
    Assert.assertFalse(instructions.contains("\nPatch:\n"));
  }

  @Test
  public void reviewCommitMessageMessageIncludesFullPatchSetContext() {
    IAiPrompt commitMessagePrompt = getAiPrompt(ReviewAssistantStages.REVIEW_COMMIT_MESSAGE);

    String reviewCommitMessage =
        commitMessagePrompt.getDefaultAiThreadReviewMessage(formattedPatchContent);

    String commitMessagePromptTemplate =
        (String)
            AiPrompt.getJsonPromptValues("promptsOpenAiReviewCommitMessage")
                .get("DEFAULT_AI_MESSAGE_REVIEW");
    Assert.assertTrue(
        reviewCommitMessage.startsWith(getTemplatePrefix(commitMessagePromptTemplate)));
    Assert.assertTrue(reviewCommitMessage.contains(formattedPatchContent));
  }

  @Test
  public void patchSetCreatedOrUpdated() throws Exception {
    String reviewMessageCode =
        getReviewMessage(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json", 0);
    String reviewMessageCommitMessage =
        getReviewMessage(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json", 1);

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    IAiPrompt openAiPromptOpenAICommitMessage =
        getAiPrompt(ReviewAssistantStages.REVIEW_COMMIT_MESSAGE);
    String reviewPrompt =
        openAiPromptOpenAICommitMessage.getDefaultAiThreadReviewMessage(formattedPatchContent);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    Assert.assertEquals(1, getCapturedComments(captor, "test_file_1.py").size());
    Assert.assertEquals(1, getCapturedComments(captor, GERRIT_PATCH_SET_FILENAME).size());

    Assert.assertEquals(reviewPrompt, requestContent);
    Assert.assertTrue(requestContent.contains(formattedPatchContent));
    Mockito.verify(pluginDataHandler)
        .setValue(
            OpenAiConversation.getMultiAgentConversationKey(ReviewAssistantStages.REVIEW_CODE),
            REVIEW_CODE_CONVERSATION_ID);
    Mockito.verify(pluginDataHandler)
        .setValue(
            OpenAiConversation.getMultiAgentConversationKey(
                ReviewAssistantStages.REVIEW_COMMIT_MESSAGE),
            REVIEW_COMMIT_MESSAGE_CONVERSATION_ID);
    Mockito.verify(pluginDataHandler, Mockito.never())
        .setValue(Mockito.eq(OpenAiConversation.KEY_CONVERSATION_ID), Mockito.anyString());
    Assert.assertEquals(reviewMessageCode, getCapturedMessage(captor, "test_file_1.py"));
    Assert.assertEquals(
        reviewMessageCommitMessage, getCapturedMessage(captor, GERRIT_PATCH_SET_FILENAME));
  }

  @Test
  public void commandReviewPatchsetScopeBypassesMultiAgentCommitMessageStage() throws Exception {
    when(pluginDataHandler.getValue(
            OpenAiConversation.getMultiAgentConversationKey(ReviewAssistantStages.REVIEW_CODE)))
        .thenReturn(REVIEW_CODE_CONVERSATION_ID);
    setupCommandComment("/review --scope=" + ReviewScope.PATCHSET.getCommandOptionValue());

    handleEventBasedOnType(SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    Assert.assertEquals(1, getCapturedComments(captor, "test_file_1.py").size());
    Assert.assertNull(captor.getValue().comments.get(GERRIT_PATCH_SET_FILENAME));
    Assert.assertEquals(
        REVIEW_CODE_CONVERSATION_ID, aiRequestBody.get("conversation").getAsString());
    Assert.assertFalse(requestContent.contains("Subject: Minor fixes"));
    Assert.assertTrue(requestContent.contains("diff --git"));
    Assert.assertFalse(
        aiRequestBody
            .get("instructions")
            .getAsString()
            .contains("You MUST review the commit message"));
    WireMock.verify(
        1, WireMock.postRequestedFor(WireMock.urlEqualTo(OpenAiUriResourceLocator.responsesUri())));
  }

  @Test
  public void commandReviewCommitMessageScopeUsesStoredCommitMessageConversation() throws Exception {
    when(pluginDataHandler.getValue(
            OpenAiConversation.getMultiAgentConversationKey(
                ReviewAssistantStages.REVIEW_COMMIT_MESSAGE)))
        .thenReturn(REVIEW_COMMIT_MESSAGE_CONVERSATION_ID);
    setupCommandComment("/review --scope=" + ReviewScope.COMMIT_MESSAGE.getCommandOptionValue());

    handleEventBasedOnType(SupportedEvents.COMMENT_ADDED);

    testRequestSent();
    Assert.assertEquals(
        REVIEW_COMMIT_MESSAGE_CONVERSATION_ID, aiRequestBody.get("conversation").getAsString());
    Assert.assertTrue(requestContent.contains("Subject: Minor fixes"));
    Assert.assertTrue(requestContent.contains("diff --git"));
    Assert.assertTrue(
        aiRequestBody
            .get("instructions")
            .getAsString()
            .contains("Git commit message expert"));
    WireMock.verify(
        1, WireMock.postRequestedFor(WireMock.urlEqualTo(OpenAiUriResourceLocator.responsesUri())));
  }

  @Test
  public void commandReviewUsesStoredMultiAgentConversations() throws Exception {
    when(pluginDataHandler.getValue(
            OpenAiConversation.getMultiAgentConversationKey(ReviewAssistantStages.REVIEW_CODE)))
        .thenReturn(REVIEW_CODE_CONVERSATION_ID);
    when(pluginDataHandler.getValue(
            OpenAiConversation.getMultiAgentConversationKey(
                ReviewAssistantStages.REVIEW_COMMIT_MESSAGE)))
        .thenReturn(REVIEW_COMMIT_MESSAGE_CONVERSATION_ID);
    setupCommandComment("/review");

    handleEventBasedOnType(SupportedEvents.COMMENT_ADDED);

    testRequestSent();
    WireMock.verify(
        1,
        WireMock.postRequestedFor(WireMock.urlEqualTo(OpenAiUriResourceLocator.responsesUri()))
            .withRequestBody(
                WireMock.matchingJsonPath(
                    "$.conversation", WireMock.equalTo(REVIEW_CODE_CONVERSATION_ID))));
    WireMock.verify(
        1,
        WireMock.postRequestedFor(WireMock.urlEqualTo(OpenAiUriResourceLocator.responsesUri()))
            .withRequestBody(
                WireMock.matchingJsonPath(
                    "$.conversation", WireMock.equalTo(REVIEW_COMMIT_MESSAGE_CONVERSATION_ID))));
    WireMock.verify(
        0,
        WireMock.postRequestedFor(WireMock.urlEqualTo(OpenAiUriResourceLocator.conversationsUri())));
  }

  private IAiPrompt getAiPrompt(ReviewAssistantStages reviewAssistantStage) {
    changeSetData.setReviewAssistantStage(reviewAssistantStage);
    return AiPromptFactory.getAiPrompt(
        config, changeSetData, getGerritChange(), getCodeContextPolicy());
  }

  private String sectionHeader(String title) {
    return "# " + title + "\n\n";
  }

  private String getTemplatePrefix(String template) {
    return template.substring(0, template.indexOf("%s"));
  }
}
