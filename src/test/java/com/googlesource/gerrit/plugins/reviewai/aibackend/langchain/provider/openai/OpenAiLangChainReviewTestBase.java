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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai;

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
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.entity.ContentType;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayInputStream;
import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.jsonToClass;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Slf4j
public class OpenAiLangChainReviewTestBase extends ReviewTestBase {
  protected static final String OPENAI_CONVERSATION_ID = "conv_TEST_CONVERSATION_ID";
  protected static final String RESOURCE_OPENAI_PATH = "__files/openai/";

  protected String formattedPatchContent;
  protected IAiPrompt openAiPrompt;
  protected String requestContent;
  protected PluginDataHandler projectHandler;

  public OpenAiLangChainReviewTestBase() {
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

    FileApi commitMessageFileMock = mock(FileApi.class);
    when(revisionApiMock.file("/COMMIT_MSG")).thenReturn(commitMessageFileMock);
    DiffInfo commitMessageDiff =
        readTestFileToClass(
            RESOURCE_OPENAI_PATH + "gerritPatchSetDiffCommitMessage.json", DiffInfo.class);
    when(commitMessageFileMock.diff(0)).thenReturn(commitMessageDiff);
  }

  protected void initComparisonContent() {
    super.initComparisonContent();
  }

  protected ArgumentCaptor<ReviewInput> testRequestSent() throws RestApiException {
    ArgumentCaptor<ReviewInput> reviewInputCaptor = super.testRequestSent();
    String requestBody = patchSetReviewer.getOpenAiClient().getRequestBody();
    if (requestBody != null && requestBody.trim().startsWith("{")) {
      aiRequestBody = jsonToClass(requestBody, JsonObject.class);
      if (aiRequestBody != null
          && aiRequestBody.has("input")
          && aiRequestBody.get("input").isJsonPrimitive()) {
        requestContent = aiRequestBody.get("input").getAsString();
      } else {
        requestContent = requestBody;
      }
    } else {
      aiRequestBody = null;
      requestContent = requestBody;
    }
    return reviewInputCaptor;
  }

  protected void setupMockRequestCreateResponseFromBody(
      String body, String fromState, String toState) {
    body = normalizeResponseBody(body);
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

  private String normalizeResponseBody(String body) {
    JsonObject response = jsonToClass(body, JsonObject.class);
    if (response == null
        || !response.has("object")
        || !"response".equals(response.get("object").getAsString())) {
      return body;
    }
    if (!response.has("created_at")) {
      response.addProperty("created_at", 1741900001);
    }
    if (!response.has("model")) {
      response.addProperty("model", config.getAiModel());
    }
    if (!response.has("usage")) {
      JsonObject usage = new JsonObject();
      usage.addProperty("input_tokens", 1);
      usage.addProperty("output_tokens", 1);
      usage.addProperty("total_tokens", 2);
      response.add("usage", usage);
    }
    return getGson().toJson(response);
  }

  protected void setupMockRequestCreateResponse(String bodyFile, String fromState, String toState) {
    setupMockRequestCreateResponseFromBody(
        readTestFile(RESOURCE_OPENAI_PATH + bodyFile), fromState, toState);
  }

  protected void setupMockRequestCreateResponse(String bodyFile) {
    setupMockRequestCreateResponse(bodyFile, null, null);
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
    if (aiRequestBody != null
        && aiRequestBody.has("input")
        && aiRequestBody.get("input").isJsonPrimitive()) {
      return aiRequestBody.get("input").getAsString();
    }
    return requestContent;
  }

  protected JsonArray getUserPromptItems() {
    String inputContent = getInputContent();
    int promptItemsStart = inputContent.indexOf("[{\"request\"");
    if (promptItemsStart < 0) {
      throw new IllegalStateException("Request JSON array not found in input: " + inputContent);
    }
    return readContentToType(inputContent.substring(promptItemsStart), JsonArray.class);
  }
}
