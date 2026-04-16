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

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponsesResponse;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.openai.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import static com.googlesource.gerrit.plugins.reviewai.listener.EventHandlerTask.SupportedEvents;
import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_PATCH_SET_FILENAME;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.mockito.Mockito.when;

@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class OpenAiReviewTaskSpecificTest extends OpenAiReviewTestBase {
  private static final String SECOND_CALL = "second-call";

  public OpenAiReviewTaskSpecificTest() {
    MockitoAnnotations.openMocks(this);
  }

  @Override
  protected void initGlobalAndProjectConfig() {
    super.initGlobalAndProjectConfig();

    when(globalConfig.getBoolean(Mockito.eq("taskSpecificAssistants"), Mockito.anyBoolean()))
        .thenReturn(true);
  }

  @Override
  protected void setupMockRequests() throws RestApiException {
    super.setupMockRequests();

    setupMockRequestCreateResponseFromBody(
        filterOutSubsetResponse(1, 2), Scenario.STARTED, SECOND_CALL);
    setupMockRequestCreateResponseFromBody(filterOutSubsetResponse(0, 1), SECOND_CALL, null);
  }

  private String filterOutSubsetResponse(int from, int to) {
    OpenAiResponsesResponse response =
        GsonUtils.jsonToClass(
            readTestFile(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json"),
            OpenAiResponsesResponse.class);
    response.getOutput().subList(from, to).clear();
    return getGson().toJson(response);
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

    Assert.assertTrue(instructions.contains("You MUST review the commit message"));
    Assert.assertTrue(
        instructions.contains(AiPrompt.DEFAULT_AI_REVIEW_PROMPT_INSTRUCTIONS_COMMIT_MESSAGES));
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
    Assert.assertEquals(reviewMessageCode, getCapturedMessage(captor, "test_file_1.py"));
    Assert.assertEquals(
        reviewMessageCommitMessage, getCapturedMessage(captor, GERRIT_PATCH_SET_FILENAME));
  }

  private IAiPrompt getAiPrompt(ReviewAssistantStages reviewAssistantStage) {
    changeSetData.setReviewAssistantStage(reviewAssistantStage);
    return AiPromptFactory.getAiPrompt(
        config, changeSetData, getGerritChange(), getCodeContextPolicy());
  }
}
