/*
 * Copyright (c) 2026. Amarula Solutions
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

import static com.googlesource.gerrit.plugins.reviewai.utils.CollectionUtils.arrayToList;

import com.google.gerrit.server.config.PluginConfig;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

final class AiAgentModelConfiguration {
  static final String KEY = "aiAgentModels";
  static final String REQUEST = "REQUEST";

  private AiAgentModelConfiguration() {}

  static Map<AiAgentRole, String> resolve(Configuration config) {
    Map<AiAgentRole, String> assignments = read(config.getGlobalConfig(), "global");
    assignments.putAll(read(config.getProjectConfig(), "project"));
    if (assignments.isEmpty()) {
      return Map.of();
    }
    List<String> models = config.getAiModels();
    assignments.forEach(
        (role, route) -> {
          if (!REQUEST.equals(route) && !models.contains(route)) {
            throw new IllegalArgumentException(
                KEY + ": " + role + " references a route outside aiModels: " + route);
          }
        });
    return Map.copyOf(assignments);
  }

  private static Map<AiAgentRole, String> read(PluginConfig scope, String scopeName) {
    Map<AiAgentRole, String> assignments = new EnumMap<>(AiAgentRole.class);
    for (String entry : arrayToList(scope.getStringList(KEY))) {
      String[] parts = entry.split("=", 2);
      if (parts.length != 2) {
        throw new IllegalArgumentException(KEY + ": expected ROLE=provider/model: " + entry);
      }
      AiAgentRole role;
      try {
        role = AiAgentRole.valueOf(parts[0].trim());
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(KEY + ": unknown role: " + parts[0].trim(), e);
      }
      String route = parts[1].trim();
      if (!REQUEST.equals(route)) {
        route =
            AiModelRoute.parse(route)
                .filter(modelRoute -> !modelRoute.model().isBlank())
                .map(AiModelRoute::modelRoute)
                .orElseThrow(() -> new IllegalArgumentException(KEY + ": invalid route: " + entry));
      }
      if (assignments.putIfAbsent(role, route) != null) {
        throw new IllegalArgumentException(
            KEY + ": duplicate role in " + scopeName + " configuration: " + role);
      }
    }
    return assignments;
  }
}
