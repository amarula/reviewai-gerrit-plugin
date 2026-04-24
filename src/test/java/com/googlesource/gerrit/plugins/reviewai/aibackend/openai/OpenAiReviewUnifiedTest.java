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
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.google.common.net.HttpHeaders;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.OpenAiUriResourceLocator;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt.AiPromptReviewReiterated;
import com.googlesource.gerrit.plugins.reviewai.utils.ThreadUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import static com.googlesource.gerrit.plugins.reviewai.listener.EventHandlerTask.SupportedEvents;
import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_PATCH_SET_FILENAME;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class OpenAiReviewUnifiedTest extends OpenAiReviewTestBase {
  private static final String REITERATE_SECOND_CALL = "reiterate-second-call";

  @Rule public TestName testName = new TestName();

  @Override
  protected void setupMockRequests() throws RestApiException {
    super.setupMockRequests();
    setupMockRequestCreateResponse("openAiRunStepsResponse.json");
  }

  @Test
  public void reviewAssistantInstructionsUseGerritUiDefaultPrompt() {
    String instructions = openAiPrompt.getDefaultAiAssistantInstructions();

    Assert.assertTrue(
        instructions.startsWith(
            sectionHeader(AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE)
                + "Remote Gerrit review prompt."));
    Assert.assertTrue(
        instructions.contains(
            "\n\n"
                + sectionHeader(
                    AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_SCOPE_AND_REVIEW_CONSTRAINTS)));
    Assert.assertTrue(
        instructions.contains(
            "\n\n"
                + sectionHeader(AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RULES)
                + "RULE #1:"));
    Assert.assertTrue(
        instructions.contains(
            "\n\n"
                + sectionHeader(
                    AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_ADDITIONAL_REVIEW_GUIDELINES)));
    Assert.assertTrue(
        instructions.contains(
            "\n\n"
                + sectionHeader(
                    AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RESPONSE_FORMAT)));
    Assert.assertTrue(instructions.contains("- the response will be only valid JSON using double-quotes"));
    Assert.assertTrue(
        instructions.contains(
            "\n\n"
                + sectionHeader(AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_EXAMPLE_RESPONSE)
                + "User: Review the following Patch Set:"));
    Assert.assertTrue(
        instructions.contains(
            "\n\n"
                + sectionHeader(AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_FIELD_DEFINITIONS)
                + "The answer object includes"));
    Assert.assertTrue(
        instructions.contains(
            "\n\n"
                + sectionHeader(
                    AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_COMMIT_MESSAGE_REVIEW_REQUIREMENT)
                + "You MUST review the commit message"));
    Assert.assertFalse(instructions.contains("Act as a PatchSet Reviewer."));
    Assert.assertFalse(instructions.contains("{{patch}}"));
    Assert.assertFalse(instructions.contains("\nPatch:\n"));
    Assert.assertFalse(instructions.contains("// MANDATORY Response format"));
    Assert.assertFalse(instructions.contains("// Example response to user"));
  }

  @Test
  public void reviewAssistantInstructionsUseConfiguredSystemPromptInsteadOfGerritUiPrompt() {
    String configuredPrompt = "Custom configured review instructions";
    when(globalConfig.getString(Mockito.eq("aiSystemPromptInstructions"), Mockito.anyString()))
        .thenReturn(configuredPrompt);
    initConfig();
    initTest();

    String instructions = openAiPrompt.getDefaultAiAssistantInstructions();

    Assert.assertTrue(
        instructions.startsWith(
            sectionHeader(AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE)
                + configuredPrompt));
    Assert.assertTrue(
        instructions.contains(
            "\n\n"
                + sectionHeader(AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RULES)
                + "RULE #1:"));
    Assert.assertFalse(instructions.contains("Remote Gerrit review prompt."));
    WireMock.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(GERRIT_UI_PROMPTS_PATH)));
  }

  @Test
  public void reviewAssistantInstructionsPutNoFileContextAfterGerritPrompt() {
    when(globalConfig.getString(Mockito.eq("codeContextPolicy"), Mockito.anyString()))
        .thenReturn("NONE");
    initConfig();
    initTest();

    String instructions = openAiPrompt.getDefaultAiAssistantInstructions();

    Assert.assertTrue(
        instructions.startsWith(
            sectionHeader(AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE)
                + "Remote Gerrit review prompt."));
    Assert.assertTrue(
        instructions.contains(
            "\n\n"
                + sectionHeader(
                    AiPromptReview.DEFAULT_AI_REVIEW_SECTION_TITLE_SCOPE_AND_REVIEW_CONSTRAINTS)));
    Assert.assertTrue(
        instructions.contains("Disregard missing implementations of methods or other code entities"));
    Assert.assertFalse(instructions.contains("Act as a PatchSet Reviewer."));
  }

  private String sectionHeader(String title) {
    return "# " + title + "\n\n";
  }

  @Test
  public void patchSetCreatedOrUpdated() throws Exception {
    String reviewMessageCode =
        getReviewMessage(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json", 0);
    String reviewMessageCommitMessage =
        getReviewMessage(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json", 1);

    String reviewPrompt = openAiPrompt.getDefaultAiThreadReviewMessage(formattedPatchContent);

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    Assert.assertEquals(reviewPrompt, requestContent);
    Assert.assertEquals(reviewMessageCode, getCapturedMessage(captor, "test_file_1.py"));
    Assert.assertEquals(
        reviewMessageCommitMessage, getCapturedMessage(captor, GERRIT_PATCH_SET_FILENAME));
  }

  @Test
  public void conversationCreateResponse400() {
    setupMockRequestCreateConversation(HTTP_BAD_REQUEST);

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    Assert.assertEquals(
        localizer.getText("message.openai.connection.error"),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void responseCreateResponse400() {
    WireMock.stubFor(
        WireMock.post(WireMock.urlEqualTo(OpenAiUriResourceLocator.responsesUri()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_BAD_REQUEST)
                    .withHeader(
                        HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    Assert.assertEquals(
        localizer.getText("message.openai.connection.error"),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void responsePollFromPending() throws Exception {
    setupMockRequestCreateResponse("openAiRunStepsEmptyResponse.json");
    setupMockRequestRetrieveResponse("openAiRunStepsEmptyResponse.json");

    try (MockedStatic<ThreadUtils> mocked = Mockito.mockStatic(ThreadUtils.class)) {
      mocked
          .when(() -> ThreadUtils.threadSleep(Mockito.anyLong()))
          .thenAnswer(
              invocation -> {
                setupMockRequestRetrieveResponse("openAiRunStepsResponse.json");
                return null;
              });

      String reviewMessageCode =
          getReviewMessage(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json", 0);
      String reviewMessageCommitMessage =
          getReviewMessage(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json", 1);

      String reviewPrompt = openAiPrompt.getDefaultAiThreadReviewMessage(formattedPatchContent);

      handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

      ArgumentCaptor<ReviewInput> captor = testRequestSent();
      Assert.assertEquals(reviewPrompt, requestContent);
      Assert.assertEquals(reviewMessageCode, getCapturedMessage(captor, "test_file_1.py"));
      Assert.assertEquals(
          reviewMessageCommitMessage, getCapturedMessage(captor, GERRIT_PATCH_SET_FILENAME));
    }
  }

  @Test
  public void responseRetrieve400() {
    setupMockRequestCreateResponse("openAiRunStepsEmptyResponse.json");
    WireMock.stubFor(
        WireMock.get(WireMock.urlEqualTo(OpenAiUriResourceLocator.responseRetrieveUri(OPENAI_RESPONSE_ID)))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_BAD_REQUEST)
                    .withHeader(
                        HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())));

    try (MockedStatic<ThreadUtils> mocked = Mockito.mockStatic(ThreadUtils.class)) {
      mocked.when(() -> ThreadUtils.threadSleep(Mockito.anyLong())).then(invocation -> null);

      handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

      Assert.assertEquals(
          localizer.getText("message.openai.connection.error"),
          changeSetData.getReviewSystemMessage());
    }
  }

  @Test
  public void patchSetCreatedReiterateRequestForTextualResponse() throws Exception {
    String reviewReiteratePrompt =
        new AiPromptReviewReiterated(
                config, changeSetData, getGerritChange(), getCodeContextPolicy())
            .getDefaultAiThreadReviewMessage("");

    setupReiterateScenarioResponse(
        "reiterate-textual",
        readTestFile(RESOURCE_OPENAI_PATH + "openAiResponseRequestMessage.json"),
        readTestFile(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json"));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    testRequestSent();
    Assert.assertEquals(reviewReiteratePrompt, requestContent);
  }

  @Test
  public void patchSetCreatedReiterateRequestForMalformedJson() throws Exception {
    String reviewReiteratePrompt =
        new AiPromptReviewReiterated(
                config, changeSetData, getGerritChange(), getCodeContextPolicy())
            .getDefaultAiThreadReviewMessage("");

    setupReiterateScenarioResponse(
        "reiterate-malformed-json",
        readTestFile(RESOURCE_OPENAI_PATH + "openAiRunStepsResponseMalformedJson.json"),
        readTestFile(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json"));

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    testRequestSent();
    Assert.assertEquals(reviewReiteratePrompt, requestContent);
  }

  @Test
  public void aiMentionedInComment() throws RestApiException {
    String reviewMessageCommitMessage =
        getReviewMessage(RESOURCE_OPENAI_PATH + "openAiResponseRequest.json", 0);

    openAiPrompt.setCommentEvent(true);
    setupMockRequestCreateResponse("openAiResponseRequest.json");

    handleEventBasedOnType(SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    assertCommentPromptPreservesHistory();
    Assert.assertEquals(
        reviewMessageCommitMessage, getCapturedMessage(captor, GERRIT_PATCH_SET_FILENAME));
  }

  @Test
  public void aiMentionedInCommentMessageResponseText() throws RestApiException {
    String reviewMessageCommitMessage =
        getReviewMessage(RESOURCE_OPENAI_PATH + "openAiResponseRequest.json", 0);

    openAiPrompt.setCommentEvent(true);
    setupMockRequestCreateResponse("openAiResponseRequestMessage.json");

    handleEventBasedOnType(SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    assertCommentPromptPreservesHistory();
    Assert.assertEquals(
        reviewMessageCommitMessage, getCapturedMessage(captor, GERRIT_PATCH_SET_FILENAME));
  }

  @Test
  public void aiMentionedInCommentMessageResponseText400() {
    openAiPrompt.setCommentEvent(true);
    setupMockRequestCreateResponse("openAiRunStepsEmptyResponse.json");
    WireMock.stubFor(
        WireMock.get(WireMock.urlEqualTo(OpenAiUriResourceLocator.responseRetrieveUri(OPENAI_RESPONSE_ID)))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_BAD_REQUEST)
                    .withHeader(
                        HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())));

    try (MockedStatic<ThreadUtils> mocked = Mockito.mockStatic(ThreadUtils.class)) {
      mocked.when(() -> ThreadUtils.threadSleep(Mockito.anyLong())).then(invocation -> null);

      handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

      Assert.assertEquals(
          localizer.getText("message.openai.connection.error"),
          changeSetData.getReviewSystemMessage());
    }
  }

  @Test
  public void aiMentionedInCommentMessageResponseJson() throws RestApiException {
    String reviewMessageCommitMessage =
        "The commit message 'Corrected Indentation in Module-Class Retrieval Line' accurately"
            + " represents the change made in the code.";

    openAiPrompt.setCommentEvent(true);
    setupMockRequestCreateResponse("openAiResponseThreadMessageJson.json");

    handleEventBasedOnType(SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    assertCommentPromptPreservesHistory();
    Assert.assertEquals(
        reviewMessageCommitMessage, getCapturedMessage(captor, GERRIT_PATCH_SET_FILENAME));
  }

  private void assertCommentPromptPreservesHistory() {
    String inputContent = getInputContent();
    Assert.assertTrue(inputContent.contains("I have some requests about the following PatchSet Diff:"));
    Assert.assertTrue(inputContent.contains(formattedPatchContent));

    JsonArray prompts = getUserPromptItems();
    Assert.assertEquals(2, prompts.size());

    JsonObject patchSetRequest = getUserPromptItem(0);
    Assert.assertEquals(
        "comment 2 (Ref. message \"message from gpt\")",
        patchSetRequest.get("request").getAsString());
    Assert.assertTrue(patchSetRequest.has("history"));
    Assert.assertTrue(patchSetRequest.get("history").getAsJsonArray().size() > 0);

    JsonObject inlineRequest = getUserPromptItem(1);
    Assert.assertEquals("message", inlineRequest.get("request").getAsString());
  }

  @Test
  public void responseCreateRequestUsesJsonSchemaStructuredOutput() throws Exception {
    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);
    testRequestSent();

    Assert.assertEquals(
        "json_schema",
        aiRequestBody.getAsJsonObject("text").getAsJsonObject("format").get("type").getAsString());
    Assert.assertEquals(
        "format_replies",
        aiRequestBody.getAsJsonObject("text").getAsJsonObject("format").get("name").getAsString());
    Assert.assertFalse(aiRequestBody.has("tools"));
  }

  @Test
  public void patchSetCreatedConvertsNeutralReviewScoreToPositiveWhenConfigured() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean())).thenReturn(true);
    when(
            globalConfig.getBoolean(
                Mockito.eq("convertNeutralReviewScoreToPositive"), Mockito.anyBoolean()))
        .thenReturn(true);
    setupMockRequestCreateResponseFromBody(
        readTestFile(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json")
            .replace("\\\"score\\\": -1.0", "\\\"score\\\": 0.0"),
        null,
        null);

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    Assert.assertEquals(1, captor.getValue().labels.get("Code-Review").intValue());
  }

  @Test
  public void patchSetCreatedAcceptsDecimalNegativeReviewScoreForVoting() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean())).thenReturn(true);
    setupMockRequestCreateResponseFromBody(
        readTestFile(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json")
            .replace("\\\"score\\\": -1.0", "\\\"score\\\": -0.4"),
        null,
        null);

    handleEventBasedOnType(SupportedEvents.PATCH_SET_CREATED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    Assert.assertEquals(-1, captor.getValue().labels.get("Code-Review").intValue());
  }

  private void setupReiterateScenarioResponse(
      String scenarioName, String firstResponseBody, String secondResponseBody) {
    WireMock.stubFor(
        WireMock.post(WireMock.urlEqualTo(OpenAiUriResourceLocator.responsesUri()))
            .atPriority(1)
            .inScenario(scenarioName)
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo(REITERATE_SECOND_CALL)
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBody(firstResponseBody)));

    WireMock.stubFor(
        WireMock.post(WireMock.urlEqualTo(OpenAiUriResourceLocator.responsesUri()))
            .atPriority(1)
            .inScenario(scenarioName)
            .whenScenarioStateIs(REITERATE_SECOND_CALL)
            .willReturn(
                WireMock.aResponse()
                    .withStatus(200)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBody(secondResponseBody)));
  }
}
