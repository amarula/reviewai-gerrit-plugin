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

import java.util.Optional;

public enum AiProviderTransport {
  OPENAI("OpenAI"),
  LANGCHAIN("LangChain");

  private final String configName;

  AiProviderTransport(String configName) {
    this.configName = configName;
  }

  public String getConfigName() {
    return configName;
  }

  public static Optional<AiProviderTransport> fromConfigName(String value) {
    for (AiProviderTransport transport : values()) {
      if (transport.configName.equalsIgnoreCase(value)
          || transport.name().equalsIgnoreCase(value)) {
        return Optional.of(transport);
      }
    }
    return Optional.empty();
  }
}
