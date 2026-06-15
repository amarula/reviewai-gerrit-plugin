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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider;

import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.model.LangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.langchain.provider.ILangChainProvider;
import dev.langchain4j.model.openai.OpenAiChatModel;
import java.time.Duration;

public abstract class OpenAiCompatibleLangChainProvider implements ILangChainProvider {

  @Override
  public LangChainProvider buildChatModel(Configuration config, double temperature) {
    String baseUrl = resolveBaseUrl(config.getAiDomain());
    String modelName = config.getAiModel();

    OpenAiChatModel.OpenAiChatModelBuilder builder =
        OpenAiChatModel.builder()
            .baseUrl(baseUrl)
            .apiKey(config.getAiToken())
            .modelName(modelName)
            .timeout(Duration.ofSeconds(config.getAiConnectionTimeout()))
            .maxRetries(LANGCHAIN_MAX_RETRIES);
    if (ModelCompatibility.supportsTemperature(modelName)) {
      builder.temperature(temperature);
    }

    return new LangChainProvider(builder.build(), baseUrl);
  }

  protected abstract String defaultBaseUrl();

  private String resolveBaseUrl(String configuredBaseUrl) {
    String baseUrl = configuredBaseUrl;
    if (baseUrl == null || baseUrl.isBlank() || Configuration.OPENAI_DOMAIN.equals(baseUrl)) {
      baseUrl = defaultBaseUrl();
    }
    if (!baseUrl.endsWith("/v1")) {
      baseUrl = baseUrl.endsWith("/") ? baseUrl + "v1" : baseUrl + "/v1";
    }
    return baseUrl;
  }
}
