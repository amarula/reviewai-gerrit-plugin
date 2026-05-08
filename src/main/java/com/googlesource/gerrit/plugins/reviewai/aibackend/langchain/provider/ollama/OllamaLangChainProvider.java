/*
 * Copyright (c) 2026. The Android Open Source Project
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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.ollama;

import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.FallbackTokenCountEstimator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.time.Duration;
import java.util.Optional;

public class OllamaLangChainProvider implements ILangChainProvider {

  @Override
  public LangChainProvider buildChatModel(Configuration config, double temperature) {
    String baseUrl = config.getOllamaDomain();

    var model =
        OllamaChatModel.builder()
            .baseUrl(baseUrl)
            .modelName(config.getAiModel())
            .temperature(temperature)
            .numCtx(config.getOllamaContextWindow())
            .numPredict(config.getOllamaResponseLength())
            .think(config.getOllamaThink())
            .timeout(Duration.ofSeconds(config.getAiConnectionTimeout()))
            .maxRetries(LANGCHAIN_MAX_RETRIES)
            .build();

    return new LangChainProvider(model, baseUrl);
  }

  @Override
  public Optional<TokenCountEstimator> createTokenEstimator(Configuration config) {
    return Optional.of(new FallbackTokenCountEstimator());
  }
}
