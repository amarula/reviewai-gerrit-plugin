/*
 * Copyright (c) 2026. Amarula Solutions
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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.config.PluginConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernWorkflowInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.LangChainProviderFactory;
import com.googlesource.gerrit.plugins.reviewai.config.AiAgentRole;
import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.TokenWindowChatMemory;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class LangChainAgentModelRoutingTest {
  private static final String PLUGIN = "reviewai-gerrit-plugin";

  @Test
  public void routesInvocationsWithProviderToolsMetricsAndCost() throws Exception {
    Configuration config = configuration(false);
    ReviewAiMetrics metrics = spy(new ReviewAiMetrics());
    TestClient client = new TestClient(config, metrics);
    GerritChange change = mock(GerritChange.class);
    ChangeSetData security = new ChangeSetData(1);
    security.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT);
    security.setSpecializedAgentName("SECURITY");
    security.setSpecializedAgentReview(true);
    ChangeSetData commitMessage = new ChangeSetData(1);
    commitMessage.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    ChangeSetData inherited = new ChangeSetData(1);
    ChangeSetData classifier = new ChangeSetData(1);
    classifier.setReviewAssistantStage(ReviewAssistantStage.CLASSIFY_REVIEW_FEEDBACK);
    List<String> routes = new ArrayList<>();
    IAiPrompt prompt = mock(IAiPrompt.class);
    String patch = read("__files/openai/gerritFormattedPatch.txt");
    String instructions = read("__files/review-agent/automaticReviewResponse.txt");
    when(prompt.getDefaultAiAssistantInstructions()).thenReturn(instructions);
    when(prompt.getDefaultAiThreadReviewMessage(anyString())).thenReturn(patch);
    ChatModel model = mock(ChatModel.class);
    when(model.chat(any(ChatRequest.class)))
        .thenReturn(
            ChatResponse.builder()
                .aiMessage(
                    AiMessage.from(
                        read("__files/langchain/aiResponseContentWithTrailingWhitespace.json")))
                .tokenUsage(new TokenUsage(10, 5))
                .build());
    ILangChainProvider adapter = mock(ILangChainProvider.class);
    when(adapter.buildChatModel(eq(config), anyDouble(), isNull(), eq(instructions)))
        .thenAnswer(
            invocation -> {
              routes.add(config.getSelectedAiModelRoute().modelRoute());
              return new LangChainProvider(model, "test");
            });

    try (var prompts = mockStatic(AiPromptFactory.class);
        var providers = mockStatic(LangChainProviderFactory.class)) {
      prompts
          .when(() -> AiPromptFactory.getAiPrompt(eq(config), any(), eq(change), isNull()))
          .thenReturn(prompt);
      for (AiProviderType provider :
          List.of(AiProviderType.OPENAI, AiProviderType.GEMINI, AiProviderType.DEEPSEEK)) {
        providers.when(() -> LangChainProviderFactory.get(provider)).thenReturn(adapter);
      }
      client.request(security, change, patch);
      client.request(commitMessage, change, patch);
      client.request(inherited, change, patch);
      client.request(classifier, change, patch);
      client.requestWithRoute(security, change, patch, config.getSelectedAiModelRoute());
      security.setSuggestMode(true);
      client.request(security, change, patch);
      security.setSuggestMode(false);
      when(change.getIsCommentEvent()).thenReturn(true);
      client.request(security, change, patch);
    }

    assertEquals(
        List.of(
            "Gemini/gemini-2.5-flash",
            "DeepSeek/deepseek-v4-flash",
            "OpenAI/gpt-4.1",
            "Gemini/gemini-2.5-flash",
            "OpenAI/gpt-4.1",
            "OpenAI/gpt-4.1",
            "OpenAI/gpt-4.1"),
        routes);
    assertEquals("OpenAI/gpt-4.1", config.getSelectedAiModelRoute().modelRoute());
    ArgumentCaptor<ChatRequest> requests = ArgumentCaptor.forClass(ChatRequest.class);
    verify(model, org.mockito.Mockito.times(7)).chat(requests.capture());
    assertNull(requests.getAllValues().get(0).responseFormat());
    assertNull(requests.getAllValues().get(1).responseFormat().jsonSchema());
    assertNotNull(requests.getAllValues().get(2).responseFormat().jsonSchema());
    assertNotNull(requests.getAllValues().get(3).responseFormat().jsonSchema());
    assertEquals(List.of(), requests.getAllValues().get(3).toolSpecifications());
    verify(metrics)
        .startAiRequest(
            AiProviderType.GEMINI,
            "gemini-2.5-flash",
            ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT,
            "SECURITY");
    verify(metrics, org.mockito.Mockito.times(2))
        .recordAiEstimatedCostNanoUsd("Gemini", "gemini-2.5-flash", 20000L);
  }

  @Test
  public void keepsParallelRoutesIsolatedAndRestoresThemAfterFailure() throws Exception {
    Configuration config = configuration(false);
    LangChainAgentModelRouting routing = new LangChainAgentModelRouting(config);
    CyclicBarrier barrier = new CyclicBarrier(2);
    var executor = Executors.newFixedThreadPool(2);
    try {
      var security =
          executor.submit(
              () ->
                  routing.execute(
                      AiAgentRole.SECURITY,
                      () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return config.getSelectedAiModelRoute().modelRoute();
                      }));
      var commit =
          executor.submit(
              () -> {
                assertThrows(
                    IllegalStateException.class,
                    () ->
                        routing.execute(
                            AiAgentRole.COMMIT_MESSAGE,
                            () -> {
                              barrier.await(5, TimeUnit.SECONDS);
                              assertEquals(
                                  "DeepSeek/deepseek-v4-flash",
                                  config.getSelectedAiModelRoute().modelRoute());
                              throw new IllegalStateException();
                            }));
                return config.getSelectedAiModelRoute().modelRoute();
              });
      assertEquals("Gemini/gemini-2.5-flash", security.get(10, TimeUnit.SECONDS));
      assertEquals("OpenAI/gpt-4.1", commit.get(10, TimeUnit.SECONDS));
      assertEquals("OpenAI/gpt-4.1", config.getSelectedAiModelRoute().modelRoute());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void requestAssignmentUsesSelectedRequestModelAndAssignmentsAreSnapshotted()
      throws Exception {
    Configuration config = configuration(true);
    config = spy(config);
    var assignments = new EnumMap<>(config.getAiAgentModels());
    doReturn(assignments).when(config).getAiAgentModels();
    LangChainAgentModelRouting routing = new LangChainAgentModelRouting(config);
    assignments.put(AiAgentRole.CORRECTNESS, "REQUEST");
    Configuration requestConfig = config;
    assertEquals(
        "DeepSeek/deepseek-v4-flash",
        routing.execute(
            AiAgentRole.SECURITY, () -> requestConfig.getSelectedAiModelRoute().modelRoute()));
    assertEquals(
        "Gemini/gemini-2.5-flash",
        routing.execute(
            AiAgentRole.CORRECTNESS, () -> requestConfig.getSelectedAiModelRoute().modelRoute()));
  }

  @Test
  public void preservesRequestModelForExcludedRequestsUnlessExplicitRouteIsProvided()
      throws Exception {
    Configuration config = configuration(false);
    LangChainAgentModelRouting routing = new LangChainAgentModelRouting(config);
    AiModelRoute requestRoute = config.getSelectedAiModelRoute();
    ChangeSetData data = new ChangeSetData(1);
    data.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT);
    data.setSpecializedAgentName("SECURITY");
    LangChainAgentModelRouting.RoutedRequest<AiModelRoute> inheritedRequest =
        routeOverridden -> {
          assertFalse(routeOverridden);
          return config.getSelectedAiModelRoute();
        };

    data.setSuggestMode(true);
    assertEquals(requestRoute, routing.execute(data, false, null, inheritedRequest));
    data.setSuggestMode(false);
    assertEquals(requestRoute, routing.execute(data, true, null, inheritedRequest));

    AiModelRoute explicitRoute =
        AiModelRoute.parse(config.getAiAgentModels().get(AiAgentRole.COMMIT_MESSAGE)).orElseThrow();
    data.setSuggestMode(true);
    assertEquals(
        explicitRoute,
        routing.execute(
            data,
            true,
            explicitRoute,
            routeOverridden -> {
              assertTrue(routeOverridden);
              return config.getSelectedAiModelRoute();
            }));
    assertEquals(requestRoute, config.getSelectedAiModelRoute());
  }

  @Test
  public void resolvesPipelineRolesAndOriginalConcernReviewers() throws Exception {
    JsonArray cases =
        JsonParser.parseString(read("__files/config/agentModelRoles.json")).getAsJsonArray();
    for (var testCase : cases) {
      ChangeSetData data = GsonUtils.getGson().fromJson(testCase, ChangeSetData.class);
      data.setConcernWorkflowInput(
          GsonUtils.getGson()
              .fromJson(
                  testCase.getAsJsonObject().get("concernWorkflowInput"),
                  ConcernWorkflowInput.class));
      assertEquals(
          testCase.toString(),
          testCase.getAsJsonObject().get("expectedRole").getAsString(),
          LangChainAgentModelRouting.roleForRequest(data).name());
    }
  }

  private Configuration configuration(boolean projectOverrides) throws Exception {
    Config global = new Config();
    global.fromText(read("__files/config/agentModels.config"));
    Config project = new Config();
    if (projectOverrides) {
      project.fromText(read("__files/config/agentModelsProject.config"));
    }
    return new Configuration(
        null,
        null,
        PluginConfig.createFromGerritConfig(PLUGIN, global),
        PluginConfig.createFromGerritConfig(PLUGIN, project),
        "",
        null);
  }

  private String read(String resource) throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/" + resource)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static class TestClient extends LangChainClient {
    TestClient(Configuration config, ReviewAiMetrics metrics) {
      super(config, null, null, null, null, null, null, metrics);
    }

    void request(ChangeSetData data, GerritChange change, String patch) throws Exception {
      askSingleRawRequest(data, change, patch);
    }

    void requestWithRoute(ChangeSetData data, GerritChange change, String patch, AiModelRoute route)
        throws Exception {
      askSingleRawRequest(data, change, patch, route);
    }

    @Override
    protected ChatMemory buildTransientMemory(Object memoryId) {
      return TokenWindowChatMemory.builder()
          .id(memoryId)
          .maxTokens(10000, mock(TokenCountEstimator.class))
          .build();
    }

    @Override
    protected boolean shouldIncludeInitialHistory(ChangeSetData data, GerritChange change) {
      return false;
    }
  }
}
