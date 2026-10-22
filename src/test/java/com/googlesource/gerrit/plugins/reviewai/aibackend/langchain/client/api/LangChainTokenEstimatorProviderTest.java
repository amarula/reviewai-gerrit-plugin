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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.config.PluginConfig;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.gemini.GeminiLangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.model.TokenCountEstimator;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

public class LangChainTokenEstimatorProviderTest {
  @Test
  @SuppressWarnings("unchecked")
  public void cachesAnEstimatorForEachProvider() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
    LangChainTokenEstimatorProvider provider = new LangChainTokenEstimatorProvider(config);
    TokenCountEstimator openAi = mock(TokenCountEstimator.class);
    TokenCountEstimator gemini = mock(TokenCountEstimator.class);
    CompletableFuture<TokenCountEstimator> openAiFuture = mock(CompletableFuture.class);
    CompletableFuture<TokenCountEstimator> geminiFuture = mock(CompletableFuture.class);
    when(openAiFuture.get(anyLong(), any())).thenReturn(openAi);
    when(geminiFuture.get(anyLong(), any())).thenReturn(gemini);
    try (var futures = mockStatic(CompletableFuture.class)) {
      futures
          .when(() -> CompletableFuture.supplyAsync(any()))
          .thenReturn(openAiFuture, geminiFuture);
      assertSame(openAi, provider.get());
      when(config.getAiProviderType()).thenReturn(AiProviderType.GEMINI);
      assertSame(gemini, provider.get());
      when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
      assertSame(openAi, provider.get());
    }
  }

  @Test
  public void carriesTheSelectedProviderIntoAsynchronousInitialization() throws Exception {
    String plugin = "reviewai-gerrit-plugin";
    Config global = new Config();
    try (InputStream input = getClass().getResourceAsStream("/__files/config/agentModels.config")) {
      global.fromText(new String(input.readAllBytes(), StandardCharsets.UTF_8));
    }
    Configuration config =
        spy(
            new Configuration(
                null,
                null,
                PluginConfig.createFromGerritConfig(plugin, global),
                PluginConfig.createFromGerritConfig(plugin, new Config()),
                "",
                null));
    AtomicReference<AiProviderType> observedProvider = new AtomicReference<>();
    doAnswer(
            invocation -> {
              observedProvider.set(config.getAiProviderType());
              return invocation.callRealMethod();
            })
        .when(config)
        .getAiToken();
    var availability = GeminiLangChainProvider.class.getDeclaredField("estimatorAvailable");
    availability.setAccessible(true);
    Object previousAvailability = availability.get(null);
    availability.set(null, null);
    try {
      LangChainTokenEstimatorProvider provider = new LangChainTokenEstimatorProvider(config);
      config.withAiModelRoute(
          new AiModelRoute(AiProviderType.GEMINI, "gemini-2.5-flash"), provider::get);

      assertEquals(AiProviderType.GEMINI, observedProvider.get());
      assertEquals(AiProviderType.OPENAI, config.getAiProviderType());
    } finally {
      availability.set(null, previousAvailability);
    }
  }

  @Test
  public void interruptedInitializationPreservesInterruptAndCachesFallback() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getAiProviderType()).thenReturn(AiProviderType.OLLAMA);
    LangChainTokenEstimatorProvider provider = new LangChainTokenEstimatorProvider(config);
    @SuppressWarnings("unchecked")
    CompletableFuture<TokenCountEstimator> pending = mock(CompletableFuture.class);
    when(pending.get(anyLong(), any())).thenThrow(new InterruptedException());

    try (var futures = mockStatic(CompletableFuture.class)) {
      futures.when(() -> CompletableFuture.supplyAsync(any())).thenReturn(pending);
      TokenCountEstimator estimator = provider.get();

      assertTrue(Thread.currentThread().isInterrupted());
      assertNotNull(estimator);
      assertSame(estimator, provider.get());
    } finally {
      Thread.interrupted();
    }
  }
}
