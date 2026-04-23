package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.gemini;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.FallbackTokenCountEstimator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import dev.langchain4j.model.TokenCountEstimator;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

public class GeminiLangChainProviderTest {

  private final GeminiLangChainProvider provider = new GeminiLangChainProvider();

  @Before
  public void resetEstimatorCache() throws Exception {
    Field field = GeminiLangChainProvider.class.getDeclaredField("estimatorAvailable");
    field.setAccessible(true);
    field.set(null, null);
  }

  @Test
  public void fallsBackToCl100kWhenGeminiModelUnsupported() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiModel()).thenReturn("gemini-fake-model");
    when(config.getAiToken()).thenReturn("dummy-token");

    Optional<TokenCountEstimator> estimator = provider.createTokenEstimator(config);

    assertTrue(estimator.isPresent());
    assertTrue(estimator.get() instanceof FallbackTokenCountEstimator);
  }

  @Test
  public void setsLangChainMaxRetriesToOne() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.GEMINI_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("gemini-2.5-flash");
    when(config.getAiConnectionTimeout()).thenReturn(30);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.0);

    assertEquals(ILangChainProvider.LANGCHAIN_MAX_RETRIES, getMaximumRetries(langChainProvider));
  }

  private static int getMaximumRetries(LangChainProvider langChainProvider) throws Exception {
    Field field = langChainProvider.getModel().getClass().getDeclaredField("maximumRetries");
    field.setAccessible(true);
    return (int) field.get(langChainProvider.getModel());
  }
}
