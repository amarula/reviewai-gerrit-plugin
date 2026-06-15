package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.deepseek;

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
import org.junit.Test;
import org.mockito.Mockito;

public class DeepSeekLangChainProviderTest {

  private final DeepSeekLangChainProvider provider = new DeepSeekLangChainProvider();

  @Test
  public void createsFallbackTokenEstimator() {
    Optional<TokenCountEstimator> estimator =
        provider.createTokenEstimator(Mockito.mock(Configuration.class));

    assertTrue(estimator.isPresent());
    assertTrue(estimator.get() instanceof FallbackTokenCountEstimator);
  }

  @Test
  public void usesDefaultDomainAndSetsLangChainMaxRetriesToOne() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.DEEPSEEK_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("deepseek-v4-flash");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.2);

    assertEquals(Configuration.DEEPSEEK_DOMAIN + "/v1", langChainProvider.getEndpoint());
    assertEquals(ILangChainProvider.LANGCHAIN_MAX_RETRIES, getMaxRetries(langChainProvider));
  }

  private static int getMaxRetries(LangChainProvider langChainProvider) throws Exception {
    Field field = langChainProvider.getModel().getClass().getDeclaredField("maxRetries");
    field.setAccessible(true);
    return (int) field.get(langChainProvider.getModel());
  }
}
