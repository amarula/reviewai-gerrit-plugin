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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.deepseek;

import static com.googlesource.gerrit.plugins.reviewai.config.Configuration.DEEPSEEK_DOMAIN;

import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.FallbackTokenCountEstimator;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.OpenAiCompatibleLangChainProvider;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import dev.langchain4j.model.TokenCountEstimator;
import java.util.Optional;

public class DeepSeekLangChainProvider extends OpenAiCompatibleLangChainProvider {

  @Override
  protected String defaultBaseUrl() {
    return DEEPSEEK_DOMAIN;
  }

  @Override
  public Optional<TokenCountEstimator> createTokenEstimator(Configuration config) {
    return Optional.of(new FallbackTokenCountEstimator());
  }
}
