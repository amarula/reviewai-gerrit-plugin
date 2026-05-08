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

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.reflect.TypeToken;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.HttpResponseFor;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.ToolChoiceOptions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiSdkClientFactory;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

@Slf4j
public class OpenAiResponsesChatModel implements ChatModel {
  private static final boolean STRICT_RESPONSE_SCHEMA = true;
  private static final boolean STRICT_TOOL_SCHEMA = false;

  private final Configuration config;
  private final String baseUrl;
  private final String modelName;
  private final Double temperature;
  private final String conversationId;
  private final String instructions;
  private final ChatRequestParameters defaultRequestParameters;

  private String requestBody;

  private OpenAiResponsesChatModel(Builder builder) {
    this.config = builder.config;
    this.baseUrl = builder.baseUrl;
    this.modelName = builder.modelName;
    this.temperature = builder.temperature;
    this.conversationId = builder.conversationId;
    this.instructions = builder.instructions;
    this.defaultRequestParameters =
        DefaultChatRequestParameters.builder()
            .modelName(modelName)
            .temperature(temperature)
            .build();
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public ChatResponse doChat(ChatRequest chatRequest) {
    ResponseCreateParams request = createRequest(chatRequest);
    requestBody = getGson().toJson(request._body());
    log.debug("OpenAI Responses LangChain request: {}", requestBody);

    OpenAIClient client = OpenAiSdkClientFactory.create(config);
    try {
      try (HttpResponseFor<Response> rawResponse =
          client.responses().withRawResponse().create(request)) {
        return toChatResponse(rawResponse.parse());
      }
    } catch (Exception e) {
      throw new RuntimeException(
          String.format(
              "OpenAI Responses LangChain request failed against `%s` with model `%s`: %s",
              OpenAiSdkClientFactory.getResolvedBaseUrl(config),
              modelName,
              OpenAiSdkClientFactory.describeException(e)),
          e);
    } finally {
      client.close();
    }
  }

  @Override
  public ChatRequestParameters defaultRequestParameters() {
    return defaultRequestParameters;
  }

  @Override
  public ModelProvider provider() {
    return ModelProvider.OPEN_AI;
  }

  @VisibleForTesting
  String getRequestBody() {
    return requestBody;
  }

  private ResponseCreateParams createRequest(ChatRequest chatRequest) {
    ResponseCreateParams.Builder builder =
        ResponseCreateParams.builder().model(getEffectiveModelName(chatRequest));

    if (conversationId != null) {
      builder.conversation(conversationId);
    }
    if (instructions != null) {
      builder.instructions(instructions);
    }

    Double effectiveTemperature = getEffectiveTemperature(chatRequest);
    if (effectiveTemperature != null) {
      builder.temperature(effectiveTemperature);
    }

    Integer maxOutputTokens =
        chatRequest.maxOutputTokens() != null
            ? chatRequest.maxOutputTokens()
            : defaultRequestParameters.maxOutputTokens();
    if (maxOutputTokens != null) {
      builder.maxOutputTokens(maxOutputTokens.longValue());
    }

    builder.inputOfResponse(toInputItems(chatRequest.messages()));

    List<ToolSpecification> toolSpecifications = chatRequest.toolSpecifications();
    if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
      for (ToolSpecification toolSpecification : toolSpecifications) {
        builder.addTool(toOpenAiFunctionTool(toolSpecification));
      }
      builder.toolChoice(toOpenAiToolChoice(chatRequest.toolChoice()));
    }

    ResponseTextConfig textConfig = toOpenAiResponseTextConfig(chatRequest.responseFormat());
    if (textConfig != null) {
      builder.text(textConfig);
    }

    return builder.build();
  }

  private String getEffectiveModelName(ChatRequest chatRequest) {
    return chatRequest.modelName() != null ? chatRequest.modelName() : modelName;
  }

  private Double getEffectiveTemperature(ChatRequest chatRequest) {
    return chatRequest.temperature() != null ? chatRequest.temperature() : temperature;
  }

  private List<ResponseInputItem> toInputItems(List<ChatMessage> messages) {
    List<ResponseInputItem> inputItems = new ArrayList<>();
    for (ChatMessage message : messages) {
      if (message instanceof SystemMessage) {
        continue;
      } else if (message instanceof UserMessage userMessage) {
        inputItems.add(toEasyInputMessage(EasyInputMessage.Role.USER, extractUserText(userMessage)));
      } else if (message instanceof AiMessage aiMessage) {
        if (aiMessage.text() != null && !aiMessage.text().isEmpty()) {
          inputItems.add(toEasyInputMessage(EasyInputMessage.Role.ASSISTANT, aiMessage.text()));
        }
        if (aiMessage.hasToolExecutionRequests()) {
          for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
            inputItems.add(
                ResponseInputItem.ofFunctionCall(
                    ResponseFunctionToolCall.builder()
                        .callId(toolRequest.id())
                        .name(toolRequest.name())
                        .arguments(toolRequest.arguments())
                        .build()));
          }
        }
      } else if (message instanceof ToolExecutionResultMessage toolResult) {
        inputItems.add(
            ResponseInputItem.ofFunctionCallOutput(
                ResponseInputItem.FunctionCallOutput.builder()
                    .callId(toolResult.id())
                    .output(toolResult.text())
                    .build()));
      } else {
        throw new IllegalArgumentException(
            "Unsupported LangChain message type for OpenAI Responses API: "
                + message.getClass().getName());
      }
    }
    return inputItems;
  }

  private ResponseInputItem toEasyInputMessage(EasyInputMessage.Role role, String text) {
    return ResponseInputItem.ofEasyInputMessage(
        EasyInputMessage.builder().role(role).content(text).build());
  }

  private String extractUserText(UserMessage userMessage) {
    if (userMessage.hasSingleText()) {
      return userMessage.singleText();
    }
    StringBuilder text = new StringBuilder();
    for (var content : userMessage.contents()) {
      if (content instanceof TextContent textContent) {
        if (!text.isEmpty()) {
          text.append("\n");
        }
        text.append(textContent.text());
      } else {
        throw new IllegalArgumentException(
            "Unsupported OpenAI Responses user content type: " + content.getClass().getName());
      }
    }
    return text.toString();
  }

  private FunctionTool toOpenAiFunctionTool(ToolSpecification toolSpecification) {
    FunctionTool.Builder builder =
        FunctionTool.builder().name(toolSpecification.name()).strict(STRICT_TOOL_SCHEMA);
    if (toolSpecification.description() != null) {
      builder.description(toolSpecification.description());
    }
    if (toolSpecification.parameters() != null) {
      Map<String, Object> parametersMap =
          JsonSchemaElementUtils.toMap(toolSpecification.parameters(), STRICT_TOOL_SCHEMA);
      builder.parameters(toSdkParameters(parametersMap));
    } else {
      builder.parameters(FunctionTool.Parameters.builder().build());
    }
    return builder.build();
  }

  private FunctionTool.Parameters toSdkParameters(Object parameters) {
    FunctionTool.Parameters.Builder builder = FunctionTool.Parameters.builder();
    for (Map.Entry<String, JsonValue> entry : toJsonValueMap(parameters).entrySet()) {
      builder.putAdditionalProperty(entry.getKey(), entry.getValue());
    }
    return builder.build();
  }

  private ToolChoiceOptions toOpenAiToolChoice(ToolChoice toolChoice) {
    if (toolChoice == ToolChoice.REQUIRED) {
      return ToolChoiceOptions.REQUIRED;
    }
    if (toolChoice == ToolChoice.NONE) {
      return ToolChoiceOptions.NONE;
    }
    return ToolChoiceOptions.AUTO;
  }

  private ResponseTextConfig toOpenAiResponseTextConfig(ResponseFormat responseFormat) {
    if (responseFormat == null || responseFormat.type() == ResponseFormatType.TEXT) {
      return null;
    }
    if (responseFormat.jsonSchema() == null) {
      return ResponseTextConfig.builder()
          .format(ResponseFormatJsonObject.builder().build())
          .build();
    }

    Map<String, Object> schemaMap =
        JsonSchemaElementUtils.toMap(
            responseFormat.jsonSchema().rootElement(), STRICT_RESPONSE_SCHEMA);
    ResponseFormatTextJsonSchemaConfig.Builder formatBuilder =
        ResponseFormatTextJsonSchemaConfig.builder()
            .name(responseFormat.jsonSchema().name())
            .schema(toSdkSchema(schemaMap))
            .strict(STRICT_RESPONSE_SCHEMA);
    return ResponseTextConfig.builder().format(formatBuilder.build()).build();
  }

  private ResponseFormatTextJsonSchemaConfig.Schema toSdkSchema(Object schema) {
    ResponseFormatTextJsonSchemaConfig.Schema.Builder builder =
        ResponseFormatTextJsonSchemaConfig.Schema.builder();
    for (Map.Entry<String, JsonValue> entry : toJsonValueMap(schema).entrySet()) {
      builder.putAdditionalProperty(entry.getKey(), entry.getValue());
    }
    return builder.build();
  }

  private Map<String, JsonValue> toJsonValueMap(Object object) {
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

  private ChatResponse toChatResponse(Response response) {
    List<ToolExecutionRequest> toolRequests = new ArrayList<>();
    StringBuilder responseText = new StringBuilder();

    for (ResponseOutputItem outputItem : response.output()) {
      if (outputItem.isMessage()) {
        appendMessageText(responseText, outputItem.asMessage());
      } else if (outputItem.isFunctionCall()) {
        ResponseFunctionToolCall functionCall = outputItem.asFunctionCall();
        toolRequests.add(
            ToolExecutionRequest.builder()
                .id(functionCall.callId())
                .name(functionCall.name())
                .arguments(functionCall.arguments())
                .build());
      }
    }

    AiMessage aiMessage =
        AiMessage.builder()
            .text(responseText.isEmpty() ? null : responseText.toString())
            .toolExecutionRequests(toolRequests.isEmpty() ? null : toolRequests)
            .build();

    TokenUsage tokenUsage = response.usage().map(this::toTokenUsage).orElse(null);
    FinishReason finishReason = getFinishReason(response, toolRequests);
    return ChatResponse.builder()
        .aiMessage(aiMessage)
        .id(response.id())
        .modelName(response.model().toString())
        .tokenUsage(tokenUsage)
        .finishReason(finishReason)
        .build();
  }

  private void appendMessageText(StringBuilder responseText, ResponseOutputMessage message) {
    for (ResponseOutputMessage.Content content : message.content()) {
      if (content.isOutputText()) {
        if (!responseText.isEmpty()) {
          responseText.append("\n");
        }
        responseText.append(content.asOutputText().text());
      }
    }
  }

  private TokenUsage toTokenUsage(ResponseUsage usage) {
    return new TokenUsage(
        toInteger(usage.inputTokens()), toInteger(usage.outputTokens()), toInteger(usage.totalTokens()));
  }

  private Integer toInteger(long value) {
    return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
  }

  private FinishReason getFinishReason(Response response, List<ToolExecutionRequest> toolRequests) {
    if (!toolRequests.isEmpty()) {
      return FinishReason.TOOL_EXECUTION;
    }
    if (response.status().isPresent()
        && ResponseStatus.INCOMPLETE.equals(response.status().get())) {
      return FinishReason.LENGTH;
    }
    if (response.status().isPresent()
        && ResponseStatus.COMPLETED.equals(response.status().get())) {
      return FinishReason.STOP;
    }
    return FinishReason.OTHER;
  }

  public static class Builder {
    private Configuration config;
    private String baseUrl;
    private String modelName;
    private Double temperature;
    private String conversationId;
    private String instructions;

    public Builder config(Configuration config) {
      this.config = config;
      return this;
    }

    public Builder baseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    public Builder modelName(String modelName) {
      this.modelName = modelName;
      return this;
    }

    public Builder temperature(Double temperature) {
      this.temperature = temperature;
      return this;
    }

    public Builder conversationId(String conversationId) {
      this.conversationId = conversationId;
      return this;
    }

    public Builder instructions(String instructions) {
      this.instructions = instructions;
      return this;
    }

    public OpenAiResponsesChatModel build() {
      return new OpenAiResponsesChatModel(this);
    }
  }
}
