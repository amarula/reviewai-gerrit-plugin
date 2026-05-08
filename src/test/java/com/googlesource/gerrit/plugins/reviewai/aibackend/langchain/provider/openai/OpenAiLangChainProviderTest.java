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
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mockito;

public class OpenAiLangChainProviderTest {

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
                    .withBody(
                        """
                        {
                          "id": "resp_test",
                          "object": "response",
                          "created_at": 1741900001,
                          "status": "completed",
                          "model": "gpt-4.1",
                          "output": [
                            {
                              "id": "msg_test",
                              "type": "message",
                              "status": "completed",
                              "role": "assistant",
                              "content": [
                                {
                                  "type": "output_text",
                                  "text": "ok",
                                  "annotations": []
                                }
                              ]
                            }
                          ],
                          "usage": {
                            "input_tokens": 4,
                            "input_tokens_details": {"cached_tokens": 0},
                            "output_tokens": 1,
                            "output_tokens_details": {"reasoning_tokens": 0},
                            "total_tokens": 5
                          }
                        }
                        """)));

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
}
