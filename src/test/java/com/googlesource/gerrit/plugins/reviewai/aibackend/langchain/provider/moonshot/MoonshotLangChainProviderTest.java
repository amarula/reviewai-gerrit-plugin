package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.moonshot;

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

public class MoonshotLangChainProviderTest {

  private final MoonshotLangChainProvider provider = new MoonshotLangChainProvider();

  @Test
  public void fallsBackToCl100kWhenMoonshotModelUnsupported() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiModel()).thenReturn("moonshot-v1-8k");

    Optional<TokenCountEstimator> estimator = provider.createTokenEstimator(config);

    assertTrue(estimator.isPresent());
    assertTrue(estimator.get() instanceof FallbackTokenCountEstimator);
  }

  @Test
  public void setsLangChainMaxRetriesToOne() throws Exception {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getAiDomain()).thenReturn(Configuration.MOONSHOT_DOMAIN);
    when(config.getAiToken()).thenReturn("dummy-token");
    when(config.getAiModel()).thenReturn("moonshot-v1-8k");
    when(config.getAiConnectionTimeout()).thenReturn(180);

    LangChainProvider langChainProvider = provider.buildChatModel(config, 0.0);

    assertEquals(ILangChainProvider.LANGCHAIN_MAX_RETRIES, getMaxRetries(langChainProvider));
  }

  private static int getMaxRetries(LangChainProvider langChainProvider) throws Exception {
    Field field = langChainProvider.getModel().getClass().getDeclaredField("maxRetries");
    field.setAccessible(true);
    return (int) field.get(langChainProvider.getModel());
  }
}
