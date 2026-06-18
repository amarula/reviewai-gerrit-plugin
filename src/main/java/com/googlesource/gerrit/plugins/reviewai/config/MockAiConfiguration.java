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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class MockAiConfiguration {
  static final String MOCK_AI_MODEL = "mock-ai";

  private static final String FALLBACK_DIRECTIVE = "FORWARD";
  private static final String FALLBACK_DIRECTIVE_PREFIX = FALLBACK_DIRECTIVE + ":";

  private final Configuration config;

  MockAiConfiguration(Configuration config) {
    this.config = config;
  }

  String getMockAiAddress() {
    return config.getMockAiAddress() == null ? "" : config.getMockAiAddress().trim();
  }

  boolean hasMockAiAddress() {
    return !getMockAiAddress().isBlank();
  }

  boolean isMockAiModelRoute(AiModelRoute route) {
    return route != null && MOCK_AI_MODEL.equals(route.model());
  }

  boolean isSelectedMockAiModelRoute(AiModelRoute route) {
    return hasMockAiAddress() && isMockAiModelRoute(route);
  }

  Optional<String> getMockAiDomain(AiModelRoute route) {
    return isSelectedMockAiModelRoute(route) ? Optional.of(getMockAiAddress()) : Optional.empty();
  }

  List<String> appendMockAiModelRoutes(List<String> modelRoutes, List<AiProviderType> providers) {
    if (!hasMockAiAddress()) {
      return modelRoutes;
    }
    List<String> modelRoutesWithMock = new ArrayList<>(modelRoutes);
    modelRoutesWithMock.addAll(
        getMockAiModelRoutes(providers).stream().map(AiModelRoute::modelRoute).toList());
    return modelRoutesWithMock.stream().distinct().toList();
  }

  List<AiModelRoute> getMockAiModelRoutes(List<AiProviderType> providers) {
    return providers.stream().map(provider -> new AiModelRoute(provider, MOCK_AI_MODEL)).toList();
  }

  Optional<AiModelRoute> getDefaultRealAiModelRoute(
      List<String> modelRoutes, String configuredDefault) {
    List<String> realModelRoutes =
        modelRoutes.stream()
            .filter(modelRoute -> modelRoute != null && !modelRoute.endsWith("/" + MOCK_AI_MODEL))
            .toList();
    if (realModelRoutes.isEmpty()) {
      return Optional.empty();
    }
    if (configuredDefault != null && !configuredDefault.isBlank()) {
      Optional<AiModelRoute> parsedRoute = AiModelRoute.parse(configuredDefault);
      if (parsedRoute.isPresent() && realModelRoutes.contains(parsedRoute.get().modelRoute())) {
        return parsedRoute;
      }
    }
    return realModelRoutes.stream().findFirst().flatMap(AiModelRoute::parse);
  }

  Optional<AiModelRoute> resolveFallbackRoute(
      String responseText, List<String> modelRoutes, String configuredDefault) {
    if (responseText == null) {
      return Optional.empty();
    }
    String directive = responseText.trim();
    if (FALLBACK_DIRECTIVE.equals(directive)) {
      return getDefaultRealAiModelRoute(modelRoutes, configuredDefault)
          .filter(route -> !isMockAiModelRoute(route));
    }
    if (!directive.startsWith(FALLBACK_DIRECTIVE_PREFIX)) {
      return Optional.empty();
    }
    String routeText = directive.substring(FALLBACK_DIRECTIVE_PREFIX.length()).trim();
    Optional<AiModelRoute> route = AiModelRoute.parse(routeText);
    if (route.isEmpty()) {
      log.warn("Ignoring invalid mock AI fallback route `{}`", routeText);
      return Optional.empty();
    }
    if (isMockAiModelRoute(route.get())) {
      log.warn(
          "Ignoring mock AI fallback route `{}` because it points to another mock model",
          routeText);
      return Optional.empty();
    }
    return route;
  }
}
