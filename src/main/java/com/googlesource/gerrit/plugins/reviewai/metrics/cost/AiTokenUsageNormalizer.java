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

import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import java.util.Optional;

final class AiTokenUsageNormalizer {
  private AiTokenUsageNormalizer() {}

  static Optional<NormalizedUsage> normalize(TokenUsage tokenUsage) {
    if (tokenUsage == null
        || tokenUsage.inputTokenCount() == null
        || tokenUsage.outputTokenCount() == null) {
      return Optional.empty();
    }

    long inputTokens = nonNegative(tokenUsage.inputTokenCount());
    long outputTokens = nonNegative(tokenUsage.outputTokenCount());
    long cachedTokens = 0;
    long cacheWriteTokens = 0;
    if (tokenUsage instanceof DetailedTokenUsage detailedTokenUsage) {
      cachedTokens = nonNegative(detailedTokenUsage.cachedInputTokenCount());
      cacheWriteTokens = nonNegative(detailedTokenUsage.cacheWriteTokenCount());
    } else if (tokenUsage instanceof OpenAiTokenUsage openAiTokenUsage
        && openAiTokenUsage.inputTokensDetails() != null) {
      cachedTokens = nonNegative(openAiTokenUsage.inputTokensDetails().cachedTokens());
    }

    cachedTokens = Math.min(cachedTokens, inputTokens);
    cacheWriteTokens = Math.min(cacheWriteTokens, inputTokens - cachedTokens);
    return Optional.of(
        new NormalizedUsage(
            inputTokens - cachedTokens - cacheWriteTokens,
            cachedTokens,
            cacheWriteTokens,
            outputTokens,
            inputTokens));
  }

  private static long nonNegative(Integer value) {
    return value == null ? 0 : Math.max(0, value.longValue());
  }

  record NormalizedUsage(
      long standardInputTokens,
      long cachedInputTokens,
      long cacheWriteTokens,
      long outputTokens,
      long totalInputTokens) {}
}
