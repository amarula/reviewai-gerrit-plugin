/*
 * Copyright (c) 2025. The Android Open Source Project
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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.ResponseEmptyRepliesException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.endpoint.OpenAiThread;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.endpoint.OpenAiThreadMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiThreadMessageResponse;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonTextUtils.isJsonObjectAsString;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonTextUtils.unwrapJsonCode;

@Slf4j
@Singleton
public class OpenAiClient extends OpenAiClientBase implements IAiClient {
  public enum ReviewAssistantStages {
    REVIEW_CODE,
    REVIEW_COMMIT_MESSAGE,
    REVIEW_REITERATED
  }

  private static final String TYPE_MESSAGE_CREATION = "message_creation";
  private static final String TYPE_TOOL_CALLS = "tool_calls";
  private static final int MAX_REITERATION_REQUESTS = 2;

  private final ICodeContextPolicy codeContextPolicy;
  private final PluginDataHandlerProvider pluginDataHandlerProvider;

  private OpenAiRunHandler openAiRunHandler;

  @VisibleForTesting
  @Inject
  public OpenAiClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider) {
    super(config);
    this.codeContextPolicy = codeContextPolicy;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    log.debug("Initialized OpenAiClient.");
  }

  public AiResponseContent ask(
      ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws AiConnectionFailException {
    isCommentEvent = change.getIsCommentEvent();
    log.debug(
        "Processing OPENAI OpenAI Request with changeId: {}, Patch Set: {}",
        change.getFullChangeId(),
        patchSet);

    AiResponseContent aiResponseContent = null;
    for (int reiterate = 0; reiterate < MAX_REITERATION_REQUESTS; reiterate++) {
      try {
        aiResponseContent = askSingleRequest(changeSetData, change, patchSet);
      } catch (ResponseEmptyRepliesException | JsonSyntaxException e) {
        log.debug("Review response in incorrect format; Requesting resend with correct format.");
        changeSetData.setForcedStagedReview(true);
        changeSetData.setReviewAssistantStage(ReviewAssistantStages.REVIEW_REITERATED);
        continue;
      }
      if (aiResponseContent == null) {
        return null;
      }
      break;
    }
    return aiResponseContent;
  }

  private AiResponseContent askSingleRequest(
      ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws AiConnectionFailException {
    log.debug("Processing Single OpenAI Request");
    String threadId = createThreadWithMessage(changeSetData, change, patchSet);
    runThread(changeSetData, change, threadId);
    AiResponseContent aiResponseContent = getResponseContentOpenAI(threadId);
    openAiRunHandler.cancelRun();
    if (!isCommentEvent && aiResponseContent.getReplies() == null) {
      throw new ResponseEmptyRepliesException();
    }
    return aiResponseContent;
  }

  private String createThreadWithMessage(
      ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws AiConnectionFailException {
    OpenAiThread openAiThread = new OpenAiThread(config, changeSetData, pluginDataHandlerProvider);
    String threadId = openAiThread.createThread();
    log.debug("Created OpenAI thread with ID: {}", threadId);

    OpenAiThreadMessage openAiThreadMessage =
        new OpenAiThreadMessage(
            threadId, config, changeSetData, change, codeContextPolicy, patchSet);
    openAiThreadMessage.addMessage();

    requestBody = openAiThreadMessage.getAddMessageRequestBody(); // Valued for testing purposes
    log.debug("OpenAI request body: {}", requestBody);

    return threadId;
  }

  private void runThread(ChangeSetData changeSetData, GerritChange change, String threadId)
      throws AiConnectionFailException {
    openAiRunHandler =
        new OpenAiRunHandler(
            threadId, config, changeSetData, change, codeContextPolicy, pluginDataHandlerProvider);
    openAiRunHandler.setupRun();
    openAiRunHandler.pollRunStep();
  }

  private AiResponseContent getResponseContentOpenAI(String threadId)
      throws AiConnectionFailException {
    return switch (openAiRunHandler.getFirstStepDetails().getType()) {
      case TYPE_MESSAGE_CREATION -> {
        log.debug("Retrieving thread message for thread ID: {}", threadId);
        yield retrieveThreadMessage(threadId);
      }
      case TYPE_TOOL_CALLS -> {
        log.debug("Processing tool calls from OpenAI run.");
        yield getResponseContent(openAiRunHandler.getFirstStepToolCalls());
      }
      default ->
          throw new IllegalStateException(
              "Unexpected Step MessageType in OpenAI OpenAI response: " + openAiRunHandler);
    };
  }

  private AiResponseContent retrieveThreadMessage(String threadId)
      throws AiConnectionFailException {
    OpenAiThreadMessage openAiThreadMessage = new OpenAiThreadMessage(threadId, config);
    String messageId = openAiRunHandler.getFirstStepDetails().getMessageCreation().getMessageId();
    log.debug("Retrieving message with ID: {}", messageId);

    OpenAiThreadMessageResponse threadMessageResponse =
        openAiThreadMessage.retrieveMessage(messageId);
    String responseText = threadMessageResponse.getContent().get(0).getText().getValue();
    if (responseText == null) {
      log.error("OpenAI thread message response content is null for message ID: {}", messageId);
      throw new RuntimeException("OpenAI thread message response content is null");
    }

    log.debug("Response text received: {}", responseText);
    if (isJsonObjectAsString(responseText)) {
      log.debug("Response text is JSON, extracting content.");
      return extractResponseContent(responseText);
    }

    log.debug("Response text is not JSON, returning as is.");
    return new AiResponseContent(responseText);
  }

  private AiResponseContent extractResponseContent(String responseText) {
    log.debug("Extracting response content from JSON.");
    return convertResponseContentFromJson(unwrapJsonCode(responseText));
  }
}
