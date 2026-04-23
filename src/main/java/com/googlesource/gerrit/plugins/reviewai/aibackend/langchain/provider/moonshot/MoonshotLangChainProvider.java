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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.moonshot;

import static com.googlesource.gerrit.plugins.reviewai.config.Configuration.MOONSHOT_DOMAIN;
import static com.googlesource.gerrit.plugins.reviewai.config.Configuration.OPENAI_DOMAIN;

import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.FallbackTokenCountEstimator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiTokenCountEstimator;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MoonshotLangChainProvider implements ILangChainProvider {

  @Override
  public LangChainProvider buildChatModel(Configuration config, double temperature) {
    String baseUrl = config.getAiDomain();
    if (baseUrl == null || baseUrl.isBlank() || OPENAI_DOMAIN.equals(baseUrl)) {
      baseUrl = MOONSHOT_DOMAIN;
    }
    if (!baseUrl.endsWith("/v1")) {
      baseUrl = baseUrl.endsWith("/") ? baseUrl + "v1" : baseUrl + "/v1";
    }

    var model =
        OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(config.getAiToken())
            .modelName(config.getAiModel())
            .temperature(temperature)
            .timeout(Duration.ofSeconds(config.getAiConnectionTimeout()))
            .maxRetries(LANGCHAIN_MAX_RETRIES)
            .build();

    return new LangChainProvider(model, baseUrl);
  }

  @Override
  public Optional<TokenCountEstimator> createTokenEstimator(Configuration config) {
    String model = config.getAiModel();
    try {
      return Optional.of(new OpenAiTokenCountEstimator(model));
    } catch (IllegalArgumentException e) {
      if (isMoonshotModel(model)) {
        log.info(
            "Moonshot model {} is not registered in jtokkit; using cl100k-based estimator.",
            model);
        log.debug("Moonshot token estimator fallback due to {}", e.getMessage(), e);
        return Optional.of(new FallbackTokenCountEstimator());
      }
      throw e;
    } catch (Throwable t) {
      log.warn(
          "Moonshot token estimator unavailable for model {}. Using approximate estimator.",
          model,
          t);
      return Optional.empty();
    }
  }

  private static boolean isMoonshotModel(String model) {
    return model != null && model.startsWith("moonshot-");
  }
}
