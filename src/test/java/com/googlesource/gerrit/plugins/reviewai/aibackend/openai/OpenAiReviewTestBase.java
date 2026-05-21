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

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.common.net.HttpHeaders;
import com.google.gerrit.extensions.api.changes.FileApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.DiffInfo;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.googlesource.gerrit.plugins.reviewai.ReviewTestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.OpenAiUriResourceLocator;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponsesResponse;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.openai.client.prompt.IAiPrompt;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.jsonToClass;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.unwrapJsonCode;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Slf4j
public class OpenAiReviewTestBase extends ReviewTestBase {
  protected static final String OPENAI_CONVERSATION_ID = "conv_TEST_CONVERSATION_ID";
  protected static final String OPENAI_RESPONSE_ID = "resp_TEST_RESPONSE_ID";
  protected static final String RESOURCE_OPENAI_PATH = "__files/openai/";

  protected String formattedPatchContent;
  protected IAiPrompt openAiPrompt;
  protected String requestContent;
  protected PluginDataHandler projectHandler;

  public OpenAiReviewTestBase() {
    MockitoAnnotations.openMocks(this);
  }

  protected void initGlobalAndProjectConfig() {
    super.initGlobalAndProjectConfig();

    setupPluginData();
    PluginDataHandlerProvider provider =
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange(), getTestReviewAiDb());
    projectHandler = provider.getProjectScope();
    Mockito.lenient().when(pluginDataHandlerProvider.getProjectScope()).thenReturn(projectHandler);
    Mockito.lenient()
        .when(pluginDataHandlerProvider.getAssistantsWorkspace())
        .thenReturn(projectHandler);
  }

  protected void initTest() {
    super.initTest();

    openAiPrompt =
        AiPromptFactory.getAiPrompt(
            config, changeSetData, getGerritChange(), getCodeContextPolicy());
  }

  protected void setupMockRequests() throws RestApiException {
    super.setupMockRequests();

    String repoJson = readTestFile(RESOURCE_OPENAI_PATH + "gitProjectFiles.json");
    Mockito.lenient()
        .when(gitRepoFiles.getGitRepoFilesAsJson(any(), any()))
        .thenReturn(List.of(repoJson));

    WireMock.stubFor(
        WireMock.post(WireMock.urlEqualTo(OpenAiUriResourceLocator.conversationsUri()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBody("{\"id\": \"" + OPENAI_CONVERSATION_ID + "\"}")));

    formattedPatchContent = readTestFile(RESOURCE_OPENAI_PATH + "gerritFormattedPatch.txt");
    ByteArrayInputStream inputStream = new ByteArrayInputStream(formattedPatchContent.getBytes());
    BinaryResult binaryResult =
        BinaryResult.create(inputStream)
            .setContentType("text/plain")
            .setContentLength(formattedPatchContent.length());
    when(revisionApiMock.patch()).thenReturn(binaryResult);

    FileApi testFileMock = mock(FileApi.class);
    when(revisionApiMock.file("test_file_1.py")).thenReturn(testFileMock);
    DiffInfo testFileDiff =
        readTestFileToClass(
            RESOURCE_OPENAI_PATH + "gerritPatchSetDiffTestFile.json", DiffInfo.class);
    when(testFileMock.diff(0)).thenReturn(testFileDiff);
  }

  protected void initComparisonContent() {
    super.initComparisonContent();
    promptTagComments = readTestFile(RESOURCE_OPENAI_PATH + "openAiPromptTagRequests.json");
  }

  protected ArgumentCaptor<ReviewInput> testRequestSent() throws RestApiException {
    ArgumentCaptor<ReviewInput> reviewInputCaptor = super.testRequestSent();
    requestContent = aiRequestBody.getAsJsonObject().get("input").getAsString();
    return reviewInputCaptor;
  }

  protected String getReviewMessage(String responseFile, int toolCallId) {
    OpenAiResponsesResponse responseContent =
        jsonToClass(readTestFile(responseFile), OpenAiResponsesResponse.class);
    List<String> responseTexts = new ArrayList<>();
    if (responseContent.getOutputText() != null && !responseContent.getOutputText().isEmpty()) {
      responseTexts.add(responseContent.getOutputText());
    } else if (responseContent.getOutput() != null) {
      for (OpenAiResponsesResponse.OutputItem outputItem : responseContent.getOutput()) {
        if (outputItem.getArguments() != null) {
          responseTexts.add(outputItem.getArguments());
          continue;
        }
        if (outputItem.getContent() == null) {
          continue;
        }
        for (OpenAiResponsesResponse.OutputItem.Content content : outputItem.getContent()) {
          if (content.getText() != null) {
            responseTexts.add(content.getText());
          }
        }
      }
    }

    AiResponseContent mergedResponse = new AiResponseContent("");
    List<com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem>
        replies = new ArrayList<>();
    for (String responseText : responseTexts) {
      AiResponseContent parsedResponse =
          jsonToClass(unwrapJsonCode(responseText), AiResponseContent.class);
      if (parsedResponse.getReplies() != null) {
        replies.addAll(parsedResponse.getReplies());
      }
    }
    mergedResponse.setReplies(replies);
    return mergedResponse.getReplies().get(toolCallId).getReply();
  }

  protected List<ReviewInput.CommentInput> getCapturedComments(
      ArgumentCaptor<ReviewInput> captor, String filename) {
    return captor.getAllValues().get(0).comments.get(filename);
  }

  protected String getCapturedMessage(ArgumentCaptor<ReviewInput> captor, String filename) {
    return getCapturedComments(captor, filename).get(0).message;
  }

  protected void setupMockRequestCreateResponseFromBody(
      String body, String fromState, String toState) {
    WireMock.stubFor(
        getScenarioMapping(
                OpenAiUriResourceLocator.responsesUri(),
                "Create-Response Scenario",
                fromState,
                toState)
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBody(body)));
  }

  protected void setupMockRequestCreateConversation(int statusCode) {
    WireMock.stubFor(
        WireMock.post(WireMock.urlEqualTo(OpenAiUriResourceLocator.conversationsUri()))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(statusCode)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())));
  }

  protected void setupMockRequestCreateResponse(String bodyFile, String fromState, String toState) {
    setupMockRequestCreateResponseFromBody(
        readTestFile(RESOURCE_OPENAI_PATH + bodyFile), fromState, toState);
  }

  protected void setupMockRequestCreateResponse(String bodyFile, String fromState) {
    setupMockRequestCreateResponse(bodyFile, fromState, null);
  }

  protected void setupMockRequestCreateResponse(String bodyFile) {
    setupMockRequestCreateResponse(bodyFile, null, null);
  }

  protected void setupMockRequestRetrieveResponseFromBody(String body, String responseId) {
    WireMock.stubFor(
        WireMock.get(WireMock.urlEqualTo(OpenAiUriResourceLocator.responseRetrieveUri(responseId)))
            .willReturn(
                WireMock.aResponse()
                    .withStatus(HTTP_OK)
                    .withHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_JSON.toString())
                    .withBody(body)));
  }

  protected void setupMockRequestRetrieveResponse(String bodyFile, String responseId) {
    setupMockRequestRetrieveResponseFromBody(readTestFile(RESOURCE_OPENAI_PATH + bodyFile), responseId);
  }

  protected void setupMockRequestRetrieveResponse(String bodyFile) {
    setupMockRequestRetrieveResponse(bodyFile, OPENAI_RESPONSE_ID);
  }

  protected MappingBuilder getScenarioMapping(
      String resourceURI, String scenario, String fromState, String toState) {
    MappingBuilder mappingBuilder = WireMock.post(WireMock.urlEqualTo(resourceURI));
    if (fromState != null) {
      mappingBuilder =
          mappingBuilder
              .inScenario(scenario)
              .whenScenarioStateIs(fromState)
              .willSetStateTo(toState);
    }
    return mappingBuilder;
  }

  protected String getUserPrompt() {
    return getUserPromptItems().get(0).getAsJsonObject().get("request").getAsString();
  }

  protected String getInputContent() {
    return aiRequestBody.get("input").getAsString();
  }

  protected JsonArray getUserPromptItems() {
    String inputContent = getInputContent();
    int promptItemsStart = inputContent.indexOf("[{\"request\"");
    if (promptItemsStart < 0) {
      throw new IllegalStateException("Request JSON array not found in input: " + inputContent);
    }
    return readContentToType(inputContent.substring(promptItemsStart), JsonArray.class);
  }

  protected JsonObject getUserPromptItem(int index) {
    return getUserPromptItems().get(index).getAsJsonObject();
  }
}
