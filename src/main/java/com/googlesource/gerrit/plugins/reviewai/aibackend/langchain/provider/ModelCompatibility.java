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

import java.util.Set;

public final class ModelCompatibility {
  private static final String GPT_5_6_MODEL_PREFIX = "gpt-5.6";
  private static final String GPT_5_5_MODEL_PREFIX = "gpt-5.5";
  private static final String KIMI_K_MODEL_PREFIX = "kimi-k";
  private static final Set<String> TEMPERATURE_UNSUPPORTED_MODEL_PREFIXES =
      Set.of(
          GPT_5_5_MODEL_PREFIX,
          GPT_5_6_MODEL_PREFIX,
          KIMI_K_MODEL_PREFIX);

  private ModelCompatibility() {}

  public static boolean supportsTemperature(String model) {
    return model == null
        || TEMPERATURE_UNSUPPORTED_MODEL_PREFIXES.stream().noneMatch(model::startsWith);
  }
}
