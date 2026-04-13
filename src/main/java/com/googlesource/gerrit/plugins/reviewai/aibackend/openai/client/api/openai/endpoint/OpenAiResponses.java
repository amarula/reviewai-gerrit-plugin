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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.endpoint;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.reflect.TypeToken;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.HttpResponseFor;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiParameters;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiPoller;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiSdkClientFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiTools;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiAssistantTools;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiCreateResponseRequest;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponseInputItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponsesResponse;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiTool;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.openai.client.prompt.IAiPrompt;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.jsonToClass;

@Slf4j
public class OpenAiResponses {
  private final Configuration config;
  @Getter private final String instructions;
  @Getter private final String model;
  @Getter private final Double temperature;
  private final ICodeContextPolicy codeContextPolicy;
  private final OpenAiPoller openAiPoller;

  private OpenAiCreateResponseRequest requestBody;

  public OpenAiResponses(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    this.config = config;
    IAiPrompt aiPromptOpenAi =
        AiPromptFactory.getAiPrompt(config, changeSetData, change, codeContextPolicy);
    OpenAiParameters openAiParameters = new OpenAiParameters(config, change.getIsCommentEvent());
    this.codeContextPolicy = codeContextPolicy;
    instructions = aiPromptOpenAi.getDefaultAiAssistantInstructions();
    model = config.getAiModel();
    temperature = openAiParameters.getAiTemperature();
    openAiPoller = new OpenAiPoller(config);
  }

  public OpenAiResponsesResponse createPromptResponse(String input, String conversationId)
      throws AiConnectionFailException {
    return createResponse(input, conversationId);
  }

  public OpenAiResponsesResponse createToolResponse(
      List<OpenAiResponseInputItem> input, String conversationId)
      throws AiConnectionFailException {
    return createResponse(input, conversationId);
  }

  public String getRequestBody() {
    return getGson().toJson(requestBody);
  }

  private OpenAiResponsesResponse createResponse(Object input, String conversationId)
      throws AiConnectionFailException {
    OpenAiAssistantTools openAiAssistantTools = buildTools();
    requestBody =
        OpenAiCreateResponseRequest.builder()
            .model(model)
            .instructions(instructions)
            .input(input)
            .temperature(temperature)
            .tools(openAiAssistantTools.getTools())
            .conversation(conversationId)
            .build();
    ResponseCreateParams sdkRequest = createSdkRequest(input, conversationId, openAiAssistantTools);
    log.debug("OpenAI Create Response request: {}", requestBody);

    OpenAIClient client = OpenAiSdkClientFactory.create(config);
    try {
      try (HttpResponseFor<Response> response =
          client.responses().withRawResponse().create(sdkRequest)) {
        String responseBody = OpenAiSdkClientFactory.readBody(response);
        OpenAiResponsesResponse openAiResponse =
            jsonToClass(responseBody, OpenAiResponsesResponse.class);
        log.debug("OpenAI Response created: {}", openAiResponse);
        return openAiPoller.runPoll(client, openAiResponse, OpenAiResponsesResponse.class);
      }
    } catch (Exception e) {
      throw new AiConnectionFailException(
          String.format(
              "OpenAI response creation failed against `%s` with model `%s`: %s",
              OpenAiSdkClientFactory.getResolvedBaseUrl(config),
              model,
              OpenAiSdkClientFactory.describeException(e)),
          e);
    } finally {
      client.close();
    }
  }

  private OpenAiAssistantTools buildTools() {
    OpenAiTools openAiFormatRepliesTools = new OpenAiTools(OpenAiTools.Functions.formatReplies);
    OpenAiAssistantTools openAiAssistantTools =
        OpenAiAssistantTools.builder()
            .tools(new ArrayList<>(List.of(openAiFormatRepliesTools.retrieveFunctionTool())))
            .build();
    codeContextPolicy.updateOpenAiTools(openAiAssistantTools);
    return openAiAssistantTools;
  }

  private ResponseCreateParams createSdkRequest(
      Object input, String conversationId, OpenAiAssistantTools openAiAssistantTools) {
    ResponseCreateParams.Builder builder =
        ResponseCreateParams.builder()
            .model(model)
            .instructions(instructions)
            .temperature(temperature)
            .conversation(conversationId);

    if (input instanceof String) {
      builder.input((String) input);
    } else if (input instanceof List<?>) {
      builder.inputOfResponse(toSdkInputItems(castToolOutputs(input)));
    } else {
      throw new IllegalArgumentException("Unsupported OpenAI input type: " + input);
    }

    for (OpenAiTool tool : openAiAssistantTools.getTools()) {
      builder.addTool(toSdkTool(tool));
    }
    return builder.build();
  }

  @SuppressWarnings("unchecked")
  private List<OpenAiResponseInputItem> castToolOutputs(Object input) {
    return (List<OpenAiResponseInputItem>) input;
  }

  private List<ResponseInputItem> toSdkInputItems(List<OpenAiResponseInputItem> toolOutputs) {
    List<ResponseInputItem> items = new ArrayList<>();
    for (OpenAiResponseInputItem toolOutput : toolOutputs) {
      items.add(
          ResponseInputItem.ofFunctionCallOutput(
              ResponseInputItem.FunctionCallOutput.builder()
                  .callId(toolOutput.getCallId())
                  .output(toolOutput.getOutput())
                  .build()));
    }
    return items;
  }

  private FunctionTool toSdkTool(OpenAiTool tool) {
    FunctionTool.Builder builder =
        FunctionTool.builder()
            .type(JsonValue.from(tool.getType()))
            .name(tool.getName())
            .strict(tool.getStrict() != null && tool.getStrict());

    if (tool.getDescription() != null) {
      builder.description(tool.getDescription());
    }
    if (tool.getParameters() != null) {
      builder.parameters(toSdkParameters(tool.getParameters()));
    }
    return builder.build();
  }

  private FunctionTool.Parameters toSdkParameters(OpenAiTool.Parameters parameters) {
    FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
    for (Map.Entry<String, JsonValue> entry : toJsonValueMap(parameters).entrySet()) {
      builder.putAdditionalProperty(entry.getKey(), entry.getValue());
    }
    return builder.build();
  }

  @VisibleForTesting
  Map<String, JsonValue> toJsonValueMap(Object object) {
    Map<String, Object> source =
        getGson()
            .fromJson(
                getGson().toJson(object), new TypeToken<Map<String, Object>>() {}.getType());
    Map<String, JsonValue> values = new LinkedHashMap<>();
    if (source == null) {
      return values;
    }
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      values.put(entry.getKey(), JsonValue.from(entry.getValue()));
    }
    return values;
  }
}
