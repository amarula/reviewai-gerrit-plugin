package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.ollama;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.FallbackTokenCountEstimator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.util.Optional;
import org.junit.Test;
import org.mockito.Mockito;

public class OllamaLangChainProviderTest {

  private final OllamaLangChainProvider provider = new OllamaLangChainProvider();

  @Test
  public void buildsModelWithDefaultLocalEndpointAndNoToken() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.OPENAI_DOMAIN);
    when(config.getAiModel()).thenReturn("llama3.2");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.2);
    OllamaChatModel model = (OllamaChatModel) langChainProvider.getModel();

    assertEquals(Configuration.OLLAMA_DOMAIN, langChainProvider.getEndpoint());
    assertEquals("llama3.2", model.defaultRequestParameters().modelName());
    assertEquals(Double.valueOf(0.2), model.defaultRequestParameters().temperature());
    verify(config, Mockito.never()).getAiToken();
  }

  @Test
  public void createTokenEstimatorUsesApproximateFallback() {
    Configuration config = Mockito.mock(Configuration.class);

    Optional<TokenCountEstimator> estimator = provider.createTokenEstimator(config);

    assertTrue(estimator.isPresent());
    assertTrue(estimator.get() instanceof FallbackTokenCountEstimator);
  }
}
