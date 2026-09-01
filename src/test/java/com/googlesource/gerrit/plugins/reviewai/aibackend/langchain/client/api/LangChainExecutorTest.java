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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.AiRequestCancellation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiCostTracker;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.mockito.Mockito;

public class LangChainExecutorTest {
  private static final String LARGE_TREE_RESOURCE = "__files/ondemand/treeLarge.txt";
  private static final String COMPRESSED_TREE_OUTPUT_RESOURCE =
      "__files/ondemand/treeCompressedRoot.txt";

  @Test
  public void continuationRequestKeepsToolResultsWhenMemoryWindowTrims() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiMaxToolResponseRounds()).thenReturn(3);

    GerritChange change = Mockito.mock(GerritChange.class);
    when(change.getFullChangeId()).thenReturn("project~branch~change");

    List<String> largeTree = readTestResource(LARGE_TREE_RESOURCE).lines().toList();
    String compressedTreeOutput = readTestResource(COMPRESSED_TREE_OUTPUT_RESOURCE).stripTrailing();
    GitRepoFiles gitRepoFiles = Mockito.mock(GitRepoFiles.class);
    when(gitRepoFiles.getPatchSetFileTree(config, change, null)).thenReturn(largeTree, largeTree);
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);

    ToolExecutionRequest firstToolRequest = toolRequest("call_1");
    ToolExecutionRequest secondToolRequest = toolRequest("call_2");
    RecordingChatModel model =
        new RecordingChatModel(
            AiMessage.from(List.of(firstToolRequest, secondToolRequest)),
            AiMessage.from("done"));
    ChatMemory memory =
        TokenWindowChatMemory.builder()
            .id("review")
            .maxTokens(1000, new TestTokenCountEstimator())
            .build();
    memory.add(UserMessage.from("review"));

    AiMessage result =
        new LangChainExecutor(
                config,
                null,
                List.of(treeToolSpecification()),
                true,
                gitRepoFiles,
                null)
            .execute(model, change, memory);

    assertEquals("done", result.text());
    assertEquals(2, model.requests.size());
    List<ChatMessage> continuationMessages = model.requests.get(1).messages();
    assertTrue(hasToolRequest(continuationMessages, "call_1"));
    assertTrue(hasToolRequest(continuationMessages, "call_2"));
    assertTrue(hasToolResult(continuationMessages, "call_1", compressedTreeOutput));
    assertTrue(hasToolResult(continuationMessages, "call_2", compressedTreeOutput));
  }

  @Test
  public void recordsCostForInitialAndContinuationRequests() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiMaxToolResponseRounds()).thenReturn(3);
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "gpt-4.1"));

    GerritChange change = Mockito.mock(GerritChange.class);
    when(change.getFullChangeId()).thenReturn("project~branch~change");
    GitRepoFiles gitRepoFiles = Mockito.mock(GitRepoFiles.class);
    when(gitRepoFiles.getPatchSetFileTree(config, change, null)).thenReturn(List.of());
    RecordingMetrics metrics = new RecordingMetrics();
    RecordingChatModel model =
        new RecordingChatModel(
            new TokenUsage(10, 2),
            AiMessage.from(List.of(toolRequest("call_1"))),
            AiMessage.from("done"));
    ChatMemory memory =
        TokenWindowChatMemory.builder()
            .id("review")
            .maxTokens(1000, new TestTokenCountEstimator())
            .build();
    memory.add(UserMessage.from("review"));

    new LangChainExecutor(
            config,
            null,
            List.of(treeToolSpecification()),
            true,
            gitRepoFiles,
            new AiCostTracker(config, metrics))
        .execute(model, change, memory);

    assertEquals(72_000L, metrics.nanoUsd);
  }

  @Test
  public void disablesToolsForContinuationAfterLastAllowedToolRound() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiMaxToolResponseRounds()).thenReturn(3);

    GerritChange change = Mockito.mock(GerritChange.class);
    when(change.getFullChangeId()).thenReturn("project~branch~change");
    GitRepoFiles gitRepoFiles = Mockito.mock(GitRepoFiles.class);
    when(gitRepoFiles.getPatchSetFileTree(config, change, null)).thenReturn(List.of());
    RecordingChatModel model =
        new RecordingChatModel(
            AiMessage.from(List.of(toolRequest("call_1"))),
            AiMessage.from(List.of(toolRequest("call_2"))),
            AiMessage.from(List.of(toolRequest("call_3"))),
            AiMessage.from("done"));
    ChatMemory memory =
        TokenWindowChatMemory.builder()
            .id("review")
            .maxTokens(1000, new TestTokenCountEstimator())
            .build();
    memory.add(UserMessage.from("review"));

    AiMessage result =
        new LangChainExecutor(
                config,
                null,
                List.of(treeToolSpecification()),
                true,
                gitRepoFiles,
                null)
            .execute(model, change, memory);

    assertEquals("done", result.text());
    assertEquals(4, model.requests.size());
    assertEquals(ToolChoice.AUTO, model.requests.get(1).toolChoice());
    assertEquals(ToolChoice.AUTO, model.requests.get(2).toolChoice());
    assertEquals(ToolChoice.NONE, model.requests.get(3).toolChoice());
    assertTrue(hasToolResult(model.requests.get(3).messages(), "call_3"));
  }

  @Test
  public void completesToolExchangeAfterReviewIsSuperseded() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiMaxToolResponseRounds()).thenReturn(3);
    GerritChange change = Mockito.mock(GerritChange.class);
    GitRepoFiles gitRepoFiles = Mockito.mock(GitRepoFiles.class);
    AiRequestCancellation cancellation = new AiRequestCancellation();
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setAiRequestCancellation(cancellation);
    RecordingChatModel model =
        new RecordingChatModel(
            AiMessage.from(List.of(toolRequest("call_1"))), AiMessage.from("done")) {
          @Override
          public ChatResponse chat(ChatRequest request) {
            ChatResponse response = super.chat(request);
            cancellation.requestSupersession("Superseded by patch set 2");
            return response;
          }
        };
    ChatMemory memory =
        TokenWindowChatMemory.builder()
            .id("review")
            .maxTokens(1000, new TestTokenCountEstimator())
            .build();
    memory.add(UserMessage.from("review"));

    AiMessage result =
        new LangChainExecutor(
                config,
                null,
                List.of(treeToolSpecification()),
                true,
                gitRepoFiles,
                null)
            .execute(model, change, changeSetData, memory);

    assertEquals("done", result.text());
    assertEquals(2, model.requests.size());
    assertTrue(hasToolResult(model.requests.get(1).messages(), "call_1"));
    assertTrue(cancellation.isSupersessionRequested());
  }

  private static ToolExecutionRequest toolRequest(String id) {
    return ToolExecutionRequest.builder().id(id).name("tree").arguments("{}").build();
  }

  private static ToolSpecification treeToolSpecification() {
    return ToolSpecification.builder()
        .name("tree")
        .parameters(JsonObjectSchema.builder().build())
        .build();
  }

  private static boolean hasToolRequest(List<ChatMessage> messages, String id) {
    return messages.stream()
        .filter(AiMessage.class::isInstance)
        .map(AiMessage.class::cast)
        .filter(AiMessage::hasToolExecutionRequests)
        .flatMap(message -> message.toolExecutionRequests().stream())
        .anyMatch(request -> id.equals(request.id()));
  }

  private static boolean hasToolResult(List<ChatMessage> messages, String id, String output) {
    return messages.stream()
        .filter(ToolExecutionResultMessage.class::isInstance)
        .map(ToolExecutionResultMessage.class::cast)
        .anyMatch(message -> id.equals(message.id()) && output.equals(message.text()));
  }

  private static boolean hasToolResult(List<ChatMessage> messages, String id) {
    return messages.stream()
        .filter(ToolExecutionResultMessage.class::isInstance)
        .map(ToolExecutionResultMessage.class::cast)
        .anyMatch(message -> id.equals(message.id()));
  }

  private String readTestResource(String resourceName) throws Exception {
    try (InputStream resource = getClass().getClassLoader().getResourceAsStream(resourceName)) {
      assertNotNull("Test resource should exist: " + resourceName, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static class RecordingChatModel implements ChatModel {
    private final List<AiMessage> responses;
    private final List<ChatRequest> requests = new ArrayList<>();
    private final TokenUsage tokenUsage;

    private RecordingChatModel(AiMessage... responses) {
      this(null, responses);
    }

    private RecordingChatModel(TokenUsage tokenUsage, AiMessage... responses) {
      this.responses = List.of(responses);
      this.tokenUsage = tokenUsage;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
      requests.add(request);
      return ChatResponse.builder()
          .aiMessage(responses.get(requests.size() - 1))
          .tokenUsage(tokenUsage)
          .build();
    }
  }

  private static class RecordingMetrics extends ReviewAiMetrics {
    private long nanoUsd;

    @Override
    public void recordAiEstimatedCostNanoUsd(String provider, String model, long nanoUsd) {
      this.nanoUsd += nanoUsd;
    }
  }

  private static class TestTokenCountEstimator implements TokenCountEstimator {
    @Override
    public int estimateTokenCountInText(String text) {
      return text == null ? 0 : Math.max(1, text.length());
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage message) {
      if (message instanceof UserMessage userMessage) {
        return estimateTokenCountInText(userMessage.singleText());
      }
      if (message instanceof AiMessage aiMessage) {
        return estimateTokenCountInText(String.valueOf(aiMessage.toolExecutionRequests()));
      }
      if (message instanceof ToolExecutionResultMessage toolResult) {
        return estimateTokenCountInText(toolResult.text());
      }
      return 1;
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> messages) {
      int count = 0;
      for (ChatMessage message : messages) {
        count += estimateTokenCountInMessage(message);
      }
      return count;
    }
  }
}
