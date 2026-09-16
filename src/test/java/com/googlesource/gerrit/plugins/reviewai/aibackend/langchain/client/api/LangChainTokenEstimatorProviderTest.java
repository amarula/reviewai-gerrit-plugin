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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.model.TokenCountEstimator;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class LangChainTokenEstimatorProviderTest {
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
