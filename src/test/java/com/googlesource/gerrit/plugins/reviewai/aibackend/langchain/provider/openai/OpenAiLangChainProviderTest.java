package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.Test;
import org.mockito.Mockito;

public class OpenAiLangChainProviderTest {

  private final OpenAiLangChainProvider provider = new OpenAiLangChainProvider();

  @Test
  public void setsLangChainMaxRetriesToOne() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.OPENAI_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gpt-4.1");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.0);

    assertEquals(ILangChainProvider.LANGCHAIN_MAX_RETRIES, getMaxRetries(langChainProvider));
  }

  @Test
  public void omitsTemperatureForGpt55() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.OPENAI_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gpt-5.5");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.2);
    OpenAiChatModel model = (OpenAiChatModel) langChainProvider.getModel();

    assertNull(model.defaultRequestParameters().temperature());
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

  private static int getMaxRetries(LangChainProvider langChainProvider) throws Exception {
    Field field = langChainProvider.getModel().getClass().getDeclaredField("maxRetries");
    field.setAccessible(true);
    return (int) field.get(langChainProvider.getModel());
  }

  private static String getEstimatorModelName(OpenAiTokenCountEstimator estimator) throws Exception {
    Field field = OpenAiTokenCountEstimator.class.getDeclaredField("modelName");
    field.setAccessible(true);
    return (String) field.get(estimator);
  }
}
