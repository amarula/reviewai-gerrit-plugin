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

package com.googlesource.gerrit.plugins.reviewai.config;

import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import java.util.Optional;

public record AiModelRoute(AiProviderType provider, String model) {
  public String providerRoute() {
    return provider.getConfigName();
  }

  public String modelRoute() {
    return providerRoute() + "/" + model;
  }

  public static Optional<AiModelRoute> parse(String route) {
    if (route == null || route.isBlank()) {
      return Optional.empty();
    }
    String[] parts = route.trim().split("/", 2);
    if (parts.length == 2) {
      return AiProviderType.fromConfigName(parts[0])
          .map(provider -> new AiModelRoute(provider, parts[1]));
    }
    return Optional.empty();
  }
}
