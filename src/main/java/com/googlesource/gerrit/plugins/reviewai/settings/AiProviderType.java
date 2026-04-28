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

package com.googlesource.gerrit.plugins.reviewai.settings;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

public enum AiProviderType {
  OPENAI("OpenAI"),
  GEMINI("Gemini"),
  MOONSHOT("MoonShot"),
  OLLAMA("Ollama");

  private static final Set<AiProviderType> DIRECT_CONNECTION_PROVIDERS = EnumSet.of(OPENAI);

  private final String configName;

  AiProviderType(String configName) {
    this.configName = configName;
  }

  public String getConfigName() {
    return configName;
  }

  public boolean supportsDirectConnection() {
    return DIRECT_CONNECTION_PROVIDERS.contains(this);
  }

  public static Optional<AiProviderType> fromConfigName(String value) {
    for (AiProviderType providerType : values()) {
      if (providerType.configName.equalsIgnoreCase(value)
          || providerType.name().equalsIgnoreCase(value)) {
        return Optional.of(providerType);
      }
    }
    return Optional.empty();
  }
}
