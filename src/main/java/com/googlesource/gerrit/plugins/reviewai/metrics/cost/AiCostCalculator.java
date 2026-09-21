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

import dev.langchain4j.model.output.TokenUsage;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.OptionalLong;

final class AiCostCalculator {
  private static final BigDecimal NANO_USD_PER_USD_PER_MILLION = new BigDecimal("1000");

  private AiCostCalculator() {}

  static OptionalLong calculateNanoUsd(ModelPricing pricing, TokenUsage tokenUsage) {
    return AiTokenUsageNormalizer.normalize(tokenUsage)
        .map(
            usage -> {
              ModelPricing.Rates rates = pricing.ratesFor(usage.totalInputTokens());
              BigDecimal nanoUsd =
                  cost(usage.standardInputTokens(), rates.inputPerMillion())
                      .add(cost(usage.cachedInputTokens(), rates.cachedInputPerMillion()))
                      .add(cost(usage.cacheWriteTokens(), rates.cacheWritePerMillion()))
                      .add(cost(usage.outputTokens(), rates.outputPerMillion()));
              return OptionalLong.of(nanoUsd.setScale(0, RoundingMode.HALF_UP).longValueExact());
            })
        .orElseGet(OptionalLong::empty);
  }

  private static BigDecimal cost(long tokens, BigDecimal usdPerMillionTokens) {
    return usdPerMillionTokens
        .multiply(BigDecimal.valueOf(tokens))
        .multiply(NANO_USD_PER_USD_PER_MILLION);
  }
}
