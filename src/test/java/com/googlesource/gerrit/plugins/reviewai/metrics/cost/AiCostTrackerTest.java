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

package com.googlesource.gerrit.plugins.reviewai.metrics.cost;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.Test;
import org.mockito.Mockito;

public class AiCostTrackerTest {
  @Test
  public void recordsPricingMissingForUnknownExactModel() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "gpt-5.4-unknown-snapshot"));
    RecordingMetrics metrics = new RecordingMetrics();

    new AiCostTracker(config, metrics).record(response());

    assertEquals(1, metrics.pricingMissing);
    assertEquals(0, metrics.nanoUsd);
  }

  @Test
  public void excludesOllamaFromCostAndMissingPricingMetrics() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OLLAMA, "in-house-model"));
    RecordingMetrics metrics = new RecordingMetrics();

    new AiCostTracker(config, metrics).record(response());

    assertEquals(0, metrics.pricingMissing);
    assertEquals(0, metrics.nanoUsd);
  }

  @Test
  public void excludesMockModelsFromCostAndMissingPricingMetrics() {
    Configuration config = Mockito.mock(Configuration.class);
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "mock-ai"));
    when(config.isSelectedMockAiModelRoute()).thenReturn(true);
    RecordingMetrics metrics = new RecordingMetrics();

    new AiCostTracker(config, metrics).record(response());

    assertEquals(0, metrics.pricingMissing);
    assertEquals(0, metrics.nanoUsd);
  }

  private static ChatResponse response() {
    return ChatResponse.builder()
        .aiMessage(AiMessage.from("ok"))
        .tokenUsage(new TokenUsage(10, 2))
        .build();
  }

  private static class RecordingMetrics extends ReviewAiMetrics {
    private long nanoUsd;
    private int pricingMissing;

    @Override
    public void recordAiEstimatedCostNanoUsd(String provider, String model, long nanoUsd) {
      this.nanoUsd += nanoUsd;
    }

    @Override
    public void recordAiPricingMissing(String provider, String model) {
      pricingMissing++;
    }
  }
}
