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
import com.google.common.net.HttpHeaders;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.OpenAiUriResourceLocator;
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

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class OpenAiReviewUnifiedTest extends OpenAiReviewTestBase {
  @Rule public TestName testName = new TestName();

  @Override
  protected void setupMockRequests() throws RestApiException {
    super.setupMockRequests();
    setupMockRequestCreateResponse("openAiRunStepsResponse.json");
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

    setupMockRequestCreateResponse("openAiResponseRequestMessage.json");

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

    setupMockRequestCreateResponse("openAiRunStepsResponseMalformedJson.json");

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
}
