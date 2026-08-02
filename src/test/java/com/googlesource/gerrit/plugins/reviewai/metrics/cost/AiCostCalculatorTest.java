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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import java.util.List;
import org.junit.Test;

public class AiCostCalculatorTest {
  @Test
  public void calculatesOpenAiCachedInputAndOutputInNanoUsd() {
    ModelPricing pricing = pricing(AiProviderType.OPENAI, "gpt-5.6-sol");
    TokenUsage usage = new DetailedTokenUsage(12000, 500, 12500, 8000, null);

    assertEquals(39_000_000L, AiCostCalculator.calculateNanoUsd(pricing, usage).orElseThrow());
  }

  @Test
  public void calculatesOpenAiCacheWritesOnlyWhenReported() {
    ModelPricing pricing = pricing(AiProviderType.OPENAI, "gpt-5.6-sol");
    TokenUsage usage = new DetailedTokenUsage(12000, 500, 12500, 8000, 1000);

    assertEquals(40_250_000L, AiCostCalculator.calculateNanoUsd(pricing, usage).orElseThrow());
  }

  @Test
  public void defaultsCacheWriteToInputRateForEachContextTier() {
    ModelPricing pricing = pricing(AiProviderType.OPENAI, "gpt-5.4");

    assertEquals(
        2_500L,
        AiCostCalculator.calculateNanoUsd(
                pricing, new DetailedTokenUsage(1, 0, 1, 0, 1))
            .orElseThrow());
    assertEquals(
        1_360_005_000L,
        AiCostCalculator.calculateNanoUsd(
                pricing, new DetailedTokenUsage(272001, 0, 272001, 0, 272001))
            .orElseThrow());
  }

  @Test
  public void switchesToLongContextPricingAboveThreshold() {
    ModelPricing pricing = pricing(AiProviderType.OPENAI, "gpt-5.6-sol");

    assertEquals(
        1_360_000_000L,
        AiCostCalculator.calculateNanoUsd(pricing, new TokenUsage(272000, 0)).orElseThrow());
    assertEquals(
        2_720_010_000L,
        AiCostCalculator.calculateNanoUsd(pricing, new TokenUsage(272001, 0)).orElseThrow());
  }

  @Test
  public void roundsFractionalNanoUsdOncePerResponse() {
    ModelPricing pricing = pricing(AiProviderType.DEEPSEEK, "deepseek-v4-pro");
    TokenUsage usage =
        OpenAiTokenUsage.builder()
            .inputTokenCount(1)
            .inputTokensDetails(
                OpenAiTokenUsage.InputTokensDetails.builder().cachedTokens(1).build())
            .outputTokenCount(0)
            .totalTokenCount(1)
            .build();

    assertEquals(4L, AiCostCalculator.calculateNanoUsd(pricing, usage).orElseThrow());
  }

  @Test
  public void overrideReplacesDefaultAndCanDefineLongTier() {
    AiPricingCatalog catalog =
        new AiPricingCatalog(
            List.of(
                "OpenAI/gpt-4.1,input=1,cachedInput=.1,cacheWrite=1.25,output=2,"
                    + "longThreshold=100,longInput=3,longCachedInput=.3,"
                    + "longCacheWrite=3.75,longOutput=4"));
    ModelPricing pricing =
        catalog.find(new AiModelRoute(AiProviderType.OPENAI, "gpt-4.1")).orElseThrow();

    assertEquals(
        701_050L,
        AiCostCalculator.calculateNanoUsd(
                pricing, new DetailedTokenUsage(101, 100, 201, 1, 1))
            .orElseThrow());
  }

  @Test
  public void modelSnapshotsAreNotMatchedByPrefix() {
    AiPricingCatalog catalog = new AiPricingCatalog(List.of());

    assertFalse(
        catalog.find(new AiModelRoute(AiProviderType.OPENAI, "gpt-5.4-2026-06-15")).isPresent());
  }

  @Test
  public void containsEveryBuiltInBillableModel() {
    AiPricingCatalog catalog = new AiPricingCatalog(List.of());
    List<AiModelRoute> routes =
        List.of(
            route(AiProviderType.OPENAI, "gpt-5.4"),
            route(AiProviderType.OPENAI, "gpt-5.5"),
            route(AiProviderType.OPENAI, "gpt-5.6-sol"),
            route(AiProviderType.OPENAI, "gpt-5.6-terra"),
            route(AiProviderType.OPENAI, "gpt-5.6-luna"),
            route(AiProviderType.OPENAI, "gpt-4.1"),
            route(AiProviderType.GEMINI, "gemini-3.1-pro"),
            route(AiProviderType.GEMINI, "gemini-3.1-flash"),
            route(AiProviderType.GEMINI, "gemini-2.5-pro"),
            route(AiProviderType.GEMINI, "gemini-2.5-flash"),
            route(AiProviderType.DEEPSEEK, "deepseek-v4-pro"),
            route(AiProviderType.DEEPSEEK, "deepseek-v4-flash"),
            route(AiProviderType.MOONSHOT, "kimi-k2.6"),
            route(AiProviderType.MOONSHOT, "kimi-k2.7-code"),
            route(AiProviderType.MOONSHOT, "kimi-k3"),
            route(AiProviderType.MOONSHOT, "moonshot-v1-8k"));

    routes.forEach(route -> assertTrue(route.toString(), catalog.find(route).isPresent()));
    assertFalse(catalog.find(route(AiProviderType.OLLAMA, "llama3.2")).isPresent());
  }

  private static ModelPricing pricing(AiProviderType provider, String model) {
    return new AiPricingCatalog(List.of()).find(route(provider, model)).orElseThrow();
  }

  private static AiModelRoute route(AiProviderType provider, String model) {
    return new AiModelRoute(provider, model);
  }
}
