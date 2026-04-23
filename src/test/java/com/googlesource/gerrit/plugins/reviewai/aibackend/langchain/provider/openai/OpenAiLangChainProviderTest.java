package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import java.lang.reflect.Field;
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

  private static int getMaxRetries(LangChainProvider langChainProvider) throws Exception {
    Field field = langChainProvider.getModel().getClass().getDeclaredField("maxRetries");
    field.setAccessible(true);
    return (int) field.get(langChainProvider.getModel());
  }
}
