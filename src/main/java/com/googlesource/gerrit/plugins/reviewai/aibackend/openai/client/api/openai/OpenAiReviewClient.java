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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai;

import com.google.common.annotations.VisibleForTesting;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiToolCall;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.endpoint.OpenAiResponses;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.ResponseEmptyRepliesException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.openai.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponsesResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonTextUtils.isJsonObjectAsString;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonTextUtils.unwrapJsonCode;

@Slf4j
@Singleton
public class OpenAiReviewClient extends OpenAiReviewClientBase implements IAiClient {
  public enum ReviewAssistantStages {
    REVIEW_CODE,
    REVIEW_COMMIT_MESSAGE,
    REVIEW_REITERATED
  }

  private static final int MAX_REITERATION_REQUESTS = 2;
  private static final String TYPE_FUNCTION_CALL = "function_call";
  private static final String TYPE_MESSAGE = "message";
  private static final String FUNCTION_FORMAT_REPLIES = "format_replies";
  private static final String MISSING_TOOL_OUTPUT_ERROR = "No tool output found for function call";

  private final ICodeContextPolicy codeContextPolicy;
  private final PluginDataHandlerProvider pluginDataHandlerProvider;

  @VisibleForTesting
  @Inject
  public OpenAiReviewClient(
      Configuration config,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider) {
    super(config);
    this.codeContextPolicy = codeContextPolicy;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    log.debug("Initialized OpenAiReviewClient.");
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
    OpenAiConversation openAiConversation =
        new OpenAiConversation(config, changeSetData, pluginDataHandlerProvider);
    OpenAiResponses openAiResponses =
        new OpenAiResponses(config, changeSetData, change, codeContextPolicy);
    String conversationId = openAiConversation.resolveConversationId();
    OpenAiResponsesResponse response =
        createPromptResponseWithRecovery(
            openAiConversation,
            openAiResponses,
            getPrompt(changeSetData, change, patchSet),
            conversationId);
    response = continueResponseLoop(openAiResponses, response, conversationId);
    AiResponseContent aiResponseContent = getResponseContentOpenAI(response);
    if (!isCommentEvent && aiResponseContent.getReplies() == null) {
      throw new ResponseEmptyRepliesException();
    }
    return aiResponseContent;
  }

  private OpenAiResponsesResponse continueResponseLoop(
      OpenAiResponses openAiResponses, OpenAiResponsesResponse response, String conversationId)
      throws AiConnectionFailException {
    while (hasToolCallsRequiringOutput(response)) {
      List<OpenAiResponsesResponse.OutputItem> functionCalls = getFunctionCalls(response);
      try {
        response =
            openAiResponses.createToolResponse(
                codeContextPolicy.buildToolResponseItems(toAiToolCalls(functionCalls)),
                response.getId());
      } finally {
        requestBody = openAiResponses.getRequestBody();
      }
    }
    return response;
  }

  private String getPrompt(ChangeSetData changeSetData, GerritChange change, String patchSet) {
    IAiPrompt openAiPrompt =
        AiPromptFactory.getAiPrompt(config, changeSetData, change, codeContextPolicy);
    return openAiPrompt.getDefaultAiThreadReviewMessage(patchSet);
  }

  private AiResponseContent getResponseContentOpenAI(OpenAiResponsesResponse response) {
    List<OpenAiResponsesResponse.OutputItem> functionCalls = getFormatRepliesCalls(response);
    if (!functionCalls.isEmpty()) {
      log.debug("Processing tool calls from OpenAI response.");
      return getResponseContent(toAiToolCalls(functionCalls));
    }

    AiResponseContent structuredResponseContent = extractStructuredResponseContent(response);
    if (structuredResponseContent != null) {
      return structuredResponseContent;
    }

    String responseText = extractResponseText(response);
    if (responseText == null) {
      throw new RuntimeException("OpenAI response content is null");
    }
    log.debug("Response text received: {}", responseText);
    if (isJsonObjectAsString(responseText)) {
      log.debug("Response text is JSON, extracting content.");
      return extractResponseContent(responseText);
    }

    log.debug("Response text is not JSON, returning as is.");
    return new AiResponseContent(responseText);
  }

  private AiResponseContent extractStructuredResponseContent(OpenAiResponsesResponse response) {
    List<AiResponseContent> structuredResponses = new ArrayList<>();
    for (String responseText : extractResponseTexts(response)) {
      if (!isJsonObjectAsString(responseText)) {
        continue;
      }
      structuredResponses.add(extractResponseContent(responseText));
    }

    if (structuredResponses.isEmpty()) {
      return null;
    }
    if (structuredResponses.size() == 1) {
      return structuredResponses.get(0);
    }
    return mergeStructuredResponses(structuredResponses);
  }

  private String extractResponseText(OpenAiResponsesResponse response) {
    List<String> responseTexts = extractResponseTexts(response);
    return responseTexts.isEmpty() ? null : responseTexts.get(0);
  }

  private boolean hasToolCallsRequiringOutput(OpenAiResponsesResponse response) {
    List<OpenAiResponsesResponse.OutputItem> functionCalls = getFunctionCalls(response);
    return functionCalls.stream().anyMatch(toolCall -> !isFormatReplies(toolCall));
  }

  private List<OpenAiResponsesResponse.OutputItem> getFunctionCalls(OpenAiResponsesResponse response) {
    List<OpenAiResponsesResponse.OutputItem> functionCalls = new ArrayList<>();
    if (response.getOutput() == null) {
      return functionCalls;
    }
    for (OpenAiResponsesResponse.OutputItem outputItem : response.getOutput()) {
      if (TYPE_FUNCTION_CALL.equals(outputItem.getType())) {
        functionCalls.add(outputItem);
      }
    }
    return functionCalls;
  }

  private List<OpenAiResponsesResponse.OutputItem> getFormatRepliesCalls(
      OpenAiResponsesResponse response) {
    List<OpenAiResponsesResponse.OutputItem> functionCalls = new ArrayList<>();
    for (OpenAiResponsesResponse.OutputItem outputItem : getFunctionCalls(response)) {
      if (isFormatReplies(outputItem)) {
        functionCalls.add(outputItem);
      }
    }
    return functionCalls;
  }

  private List<AiToolCall> toAiToolCalls(List<OpenAiResponsesResponse.OutputItem> functionCalls) {
    List<AiToolCall> aiToolCalls = new ArrayList<>();
    for (OpenAiResponsesResponse.OutputItem functionCall : functionCalls) {
      if (functionCall.getName() == null) {
        continue;
      }
      AiToolCall toolCall = new AiToolCall();
      toolCall.setId(functionCall.getCallId());
      toolCall.setType("function");

      AiToolCall.Function function = new AiToolCall.Function();
      function.setName(functionCall.getName());
      function.setArguments(functionCall.getArguments());
      toolCall.setFunction(function);

      aiToolCalls.add(toolCall);
    }
    return aiToolCalls;
  }

  private boolean isFormatReplies(OpenAiResponsesResponse.OutputItem functionCall) {
    return FUNCTION_FORMAT_REPLIES.equals(functionCall.getName());
  }

  private AiResponseContent extractResponseContent(String responseText) {
    log.debug("Extracting response content from JSON.");
    return convertResponseContentFromJson(unwrapJsonCode(responseText));
  }

  private OpenAiResponsesResponse createPromptResponseWithRecovery(
      OpenAiConversation openAiConversation,
      OpenAiResponses openAiResponses,
      String prompt,
      String conversationId)
      throws AiConnectionFailException {
    try {
      return openAiResponses.createPromptResponse(prompt, conversationId);
    } catch (AiConnectionFailException e) {
      if (!isMissingToolOutputError(e)) {
        throw e;
      }
      log.warn(
          "OpenAI conversation {} has unresolved tool calls. Resetting it and retrying once.",
          conversationId);
      openAiConversation.clear();
      return openAiResponses.createPromptResponse(prompt, openAiConversation.resolveConversationId());
    } finally {
      requestBody = openAiResponses.getRequestBody();
    }
  }

  private List<String> extractResponseTexts(OpenAiResponsesResponse response) {
    List<String> responseTexts = new ArrayList<>();
    if (response.getOutputText() != null && !response.getOutputText().isEmpty()) {
      responseTexts.add(response.getOutputText());
      return responseTexts;
    }

    if (response.getOutput() == null) {
      return responseTexts;
    }
    for (OpenAiResponsesResponse.OutputItem outputItem : response.getOutput()) {
      if (!TYPE_MESSAGE.equals(outputItem.getType()) || outputItem.getContent() == null) {
        continue;
      }
      for (OpenAiResponsesResponse.OutputItem.Content content : outputItem.getContent()) {
        if (content.getText() != null) {
          responseTexts.add(content.getText());
        }
      }
    }
    return responseTexts;
  }

  private AiResponseContent mergeStructuredResponses(List<AiResponseContent> structuredResponses) {
    List<AiReplyItem> replies = new ArrayList<>();
    String changeId = null;
    for (AiResponseContent structuredResponse : structuredResponses) {
      if (structuredResponse.getReplies() != null) {
        replies.addAll(structuredResponse.getReplies());
      }
      if (changeId == null && structuredResponse.getChangeId() != null) {
        changeId = structuredResponse.getChangeId();
      }
    }

    AiResponseContent mergedResponse = new AiResponseContent("");
    mergedResponse.setReplies(replies.isEmpty() ? null : replies);
    mergedResponse.setChangeId(changeId);
    return mergedResponse;
  }

  private boolean isMissingToolOutputError(AiConnectionFailException exception) {
    if (exception == null || exception.getMessage() == null) {
      return false;
    }
    return exception.getMessage().contains(MISSING_TOOL_OUTPUT_ERROR);
  }
}
