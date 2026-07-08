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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

final class DevMockAiConfigurationBridge {
  private static final String MOCK_AI_CONFIGURATION_CLASS =
      "com.googlesource.gerrit.plugins.reviewai.config.MockAiConfiguration";

  private DevMockAiConfigurationBridge() {}

  static Optional<String> getMockAiDomain(Configuration config, AiModelRoute route) {
    return invoke(config, "getMockAiDomain", new Class<?>[] {AiModelRoute.class}, route)
        .map(Optional.class::cast)
        .orElse(Optional.empty());
  }

  @SuppressWarnings("unchecked")
  static List<String> appendMockAiModelRoutes(
      Configuration config,
      List<String> modelRoutes,
      List<AiProviderType> providers,
      boolean includeMockAiModels) {
    return invoke(
            config,
            "appendMockAiModelRoutes",
            new Class<?>[] {List.class, List.class, boolean.class},
            modelRoutes,
            providers,
            includeMockAiModels)
        .map(value -> (List<String>) value)
        .orElse(modelRoutes);
  }

  static Optional<AiModelRoute> getDefaultRealAiModelRoute(
      Configuration config, List<String> modelRoutes, String configuredDefault) {
    return invoke(
            config,
            "getDefaultRealAiModelRoute",
            new Class<?>[] {List.class, String.class},
            modelRoutes,
            configuredDefault)
        .map(Optional.class::cast)
        .orElse(Optional.empty());
  }

  static Optional<AiModelRoute> resolveFallbackRoute(
      Configuration config, String responseText, List<String> modelRoutes, String configuredDefault) {
    return invoke(
            config,
            "resolveFallbackRoute",
            new Class<?>[] {String.class, List.class, String.class},
            responseText,
            modelRoutes,
            configuredDefault)
        .map(Optional.class::cast)
        .orElse(Optional.empty());
  }

  static boolean isSelectedMockAiModelRoute(Configuration config, AiModelRoute route) {
    return invoke(config, "isSelectedMockAiModelRoute", new Class<?>[] {AiModelRoute.class}, route)
        .map(Boolean.class::cast)
        .orElse(false);
  }

  private static Optional<Object> invoke(
      Configuration config, String methodName, Class<?>[] parameterTypes, Object... args) {
    try {
      Class<?> mockAiConfigurationClass = Class.forName(MOCK_AI_CONFIGURATION_CLASS);
      Constructor<?> constructor = mockAiConfigurationClass.getDeclaredConstructor(Configuration.class);
      constructor.setAccessible(true);
      Object mockAiConfiguration = constructor.newInstance(config);
      Method method = mockAiConfigurationClass.getDeclaredMethod(methodName, parameterTypes);
      method.setAccessible(true);
      return Optional.ofNullable(method.invoke(mockAiConfiguration, args));
    } catch (ReflectiveOperationException e) {
      return Optional.empty();
    }
  }
}
