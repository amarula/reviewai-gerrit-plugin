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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand.OnDemandCodeContextTools;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
class LangChainToolExecutor {

  private static final int MAX_TOOL_EXECUTION_ROUNDS = 3;

  private final Configuration config;
  private final ResponseFormat structuredResponseFormat;
  private final List<ToolSpecification> onDemandTools;
  private final boolean requireInitialToolUse;

  AiMessage execute(ChatModel model, GerritChange change, ChatMemory memory) {
    ChatRequest initialRequest = buildChatRequest(memory.messages(), getInitialToolChoice());
    ChatResponse response = model.chat(initialRequest);
    AiMessage aiMessage = response != null ? response.aiMessage() : null;
    logAiMessageToolRequests("initial", aiMessage);

    int iteration = 0;
    while (aiMessage != null
        && aiMessage.hasToolExecutionRequests()
        && iteration < MAX_TOOL_EXECUTION_ROUNDS) {
      iteration++;
      memory.add(aiMessage);
      List<ToolExecutionRequest> requests = aiMessage.toolExecutionRequests();
      if (requests == null || requests.isEmpty()) {
        break;
      }
      for (ToolExecutionRequest request : requests) {
        String output = executeToolRequest(request, change);
        memory.add(ToolExecutionResultMessage.from(request, output));
      }
      response = model.chat(buildChatRequest(memory.messages(), ToolChoice.AUTO));
      aiMessage = response != null ? response.aiMessage() : null;
      logAiMessageToolRequests("tool-continuation-" + iteration, aiMessage);
    }

    if (aiMessage != null && aiMessage.hasToolExecutionRequests()) {
      log.warn(
          "LangChain on-demand tool execution stopped after {} rounds with pending tool requests: {}",
          MAX_TOOL_EXECUTION_ROUNDS,
          aiMessage.toolExecutionRequests());
    }

    return aiMessage;
  }

  private ChatRequest buildChatRequest(List<ChatMessage> messages, ToolChoice toolChoice) {
    ChatRequest.Builder requestBuilder = ChatRequest.builder().messages(messages);

    var parametersBuilder = ChatRequestParameters.builder();
    boolean parametersUsed = false;

    if (onDemandTools != null && !onDemandTools.isEmpty()) {
      parametersBuilder.toolSpecifications(onDemandTools).toolChoice(toolChoice);
      parametersUsed = true;
      log.debug(
          "LangChain on-demand tools exposed: names={}, toolChoice={}, structuredResponse={}",
          getToolNames(),
          toolChoice,
          structuredResponseFormat != null);
    }

    if (structuredResponseFormat != null) {
      if (!parametersUsed) {
        requestBuilder.responseFormat(structuredResponseFormat);
      } else {
        parametersBuilder.responseFormat(structuredResponseFormat);
      }
    }

    if (parametersUsed) {
      requestBuilder.parameters(parametersBuilder.build());
    }

    return requestBuilder.build();
  }

  private ToolChoice getInitialToolChoice() {
    return requireInitialToolUse ? ToolChoice.REQUIRED : ToolChoice.AUTO;
  }

  private String executeToolRequest(ToolExecutionRequest request, GerritChange change) {
    if (request == null || onDemandTools == null || onDemandTools.isEmpty()) {
      return "";
    }

    String toolName = request.name();
    if (!OnDemandCodeContextTools.FUNCTION_NAMES.contains(toolName)) {
      log.debug("Ignoring unsupported tool request: {}", toolName);
      return "";
    }

    String arguments = request.arguments();
    OnDemandCodeContextTools codeContextTools =
        new OnDemandCodeContextTools(config, change, new GitRepoFiles());
    return codeContextTools.execute(toolName, arguments);
  }

  private List<String> getToolNames() {
    if (onDemandTools == null) {
      return List.of();
    }
    return onDemandTools.stream().map(ToolSpecification::name).toList();
  }

  private void logAiMessageToolRequests(String stage, AiMessage aiMessage) {
    if (aiMessage == null) {
      log.debug("LangChain response at {} is null", stage);
      return;
    }
    if (aiMessage.hasToolExecutionRequests()) {
      log.info(
          "LangChain response at {} requested on-demand tools: {}",
          stage,
          aiMessage.toolExecutionRequests());
      return;
    }
    log.debug("LangChain response at {} did not request on-demand tools", stage);
  }
}
