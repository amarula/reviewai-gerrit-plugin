package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

public class OpenAiLangChainProviderTest {
  private static final String OPENAI_RESPONSES_SUCCESS_RESOURCE =
      "__files/langchain/openAiResponsesSuccess.json";

  @Rule public WireMockRule wireMockRule = new WireMockRule(0);

  private final OpenAiLangChainProvider provider = new OpenAiLangChainProvider();

  @Test
  public void usesResponsesApiAndConversationId() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn("http://localhost:" + wireMockRule.port());
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gpt-4.1");
    when(config.getAiConnectionTimeout()).thenReturn(5);
    when(config.getAiConnectionMaxRetryAttempts()).thenReturn(1);
    WireMock.stubFor(
        post(urlEqualTo("/v1/responses"))
            .willReturn(
                WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(successfulResponseBody())));

    LangChainProvider langChainProvider =
        provider.buildChatModel(config, 0.0, "conv_test", "review instructions");
    ChatResponse response =
        langChainProvider
            .getModel()
            .chat(
                ChatRequest.builder()
                    .messages(List.of(SystemMessage.from("system message"), UserMessage.from("Say ok")))
                    .build());

    assertTrue(langChainProvider.getModel() instanceof OpenAiResponsesChatModel);
    assertEquals("ok", response.aiMessage().text());
    WireMock.verify(
        1,
        postRequestedFor(urlEqualTo("/v1/responses"))
            .withRequestBody(matchingJsonPath("$.conversation", equalTo("conv_test")))
            .withRequestBody(matchingJsonPath("$.instructions", equalTo("review instructions")))
            .withRequestBody(matchingJsonPath("$.input[0].role", equalTo("user"))));
    WireMock.verify(0, postRequestedFor(urlEqualTo("/v1/chat/completions")));
  }

  @Test
  public void serializesToolParametersAsJsonObjectSchema() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn("http://localhost:" + wireMockRule.port());
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gpt-4.1");
    when(config.getAiConnectionTimeout()).thenReturn(5);
    when(config.getAiConnectionMaxRetryAttempts()).thenReturn(1);
    WireMock.stubFor(
        post(urlEqualTo("/v1/responses"))
            .willReturn(
                WireMock.aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(successfulResponseBody())));

    ToolSpecification treeTool =
        ToolSpecification.builder()
            .name("tree")
            .description("Return the repository file tree.")
            .parameters(
                JsonObjectSchema.builder()
                    .addProperty("subdir", JsonStringSchema.builder().build())
                    .required(List.of())
                    .build())
            .build();

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.0, "conv_test");
    langChainProvider
        .getModel()
        .chat(
            ChatRequest.builder()
                .messages(UserMessage.from("Use a tool"))
                .parameters(
                    ChatRequestParameters.builder()
                        .toolSpecifications(List.of(treeTool))
                        .toolChoice(ToolChoice.REQUIRED)
                        .build())
                .build());

    WireMock.verify(
        1,
        postRequestedFor(urlEqualTo("/v1/responses"))
            .withRequestBody(matchingJsonPath("$.tools[0].parameters.type", equalTo("object")))
            .withRequestBody(matchingJsonPath("$.tools[0].strict", equalTo("false"))));
  }

  @Test
  public void omitsTemperatureForGpt55() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.OPENAI_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gpt-5.5");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.2);
    OpenAiResponsesChatModel model = (OpenAiResponsesChatModel) langChainProvider.getModel();

    assertNull(model.defaultRequestParameters().temperature());
  }

  @Test
  public void usesResponsesModelWithoutConversationWhenNotProvided() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.OPENAI_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gpt-4.1");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.0);

    assertTrue(langChainProvider.getModel() instanceof OpenAiResponsesChatModel);
    assertFalse(langChainProvider.getModel().getClass().getName().contains("OpenAiChatModel"));
  }

  @Test
  public void createTokenEstimatorUsesDefaultOpenAiModel() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    // Deliberately return a different provider model to prove the estimator ignores config and
    // uses the OpenAI default model constant instead.
    when(config.getAiModel()).thenReturn("moonshot-v1-8k");

    Optional<TokenCountEstimator> estimator = provider.createTokenEstimator(config);

    assertTrue(estimator.isPresent());
    assertEquals(
        Configuration.DEFAULT_OPENAI_ESTIMATOR_MODEL,
        getEstimatorModelName((OpenAiTokenCountEstimator) estimator.get()));
  }

  private static String getEstimatorModelName(OpenAiTokenCountEstimator estimator) throws Exception {
    Field field = OpenAiTokenCountEstimator.class.getDeclaredField("modelName");
    field.setAccessible(true);
    return (String) field.get(estimator);
  }

  private static String successfulResponseBody() {
    try (InputStream inputStream =
        OpenAiLangChainProviderTest.class
            .getClassLoader()
            .getResourceAsStream(OPENAI_RESPONSES_SUCCESS_RESOURCE)) {
      if (inputStream == null) {
        throw new IllegalStateException(
            "Missing test resource: " + OPENAI_RESPONSES_SUCCESS_RESOURCE);
      }
      return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new RuntimeException(
          "Failed to read test resource: " + OPENAI_RESPONSES_SUCCESS_RESOURCE, e);
    }
  }
}
