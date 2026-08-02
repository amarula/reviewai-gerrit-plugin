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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.ResourceUtils.forEachResourceFile;

@Slf4j
public final class SpecializedReviewAgentDefinitions {
  private static final String RESOURCE_DIR = "config/agents/level2/specialized";
  private static final String JSON_SUFFIX = ".json";
  private static volatile List<SpecializedReviewAgentDefinition> cachedDefinitions;

  private SpecializedReviewAgentDefinitions() {}

  public static List<SpecializedReviewAgentDefinition> load() {
    List<SpecializedReviewAgentDefinition> definitions = cachedDefinitions;
    if (definitions != null) {
      return definitions;
    }
    synchronized (SpecializedReviewAgentDefinitions.class) {
      if (cachedDefinitions == null) {
        cachedDefinitions = loadDefinitions();
      }
      return cachedDefinitions;
    }
  }

  private static List<SpecializedReviewAgentDefinition> loadDefinitions() {
    ClassLoader classLoader = SpecializedReviewAgentDefinitions.class.getClassLoader();
    Map<String, SpecializedReviewAgentDefinition> definitions = new LinkedHashMap<>();
    try {
      forEachResourceFile(
          classLoader,
          RESOURCE_DIR,
          JSON_SUFFIX,
          (resourceName, inputStream) -> addDefinition(definitions, resourceName, inputStream));
    } catch (Exception e) {
      log.warn("Unable to load specialized review agent definitions", e);
    }
    return definitions.values().stream()
        .sorted(Comparator.comparing(SpecializedReviewAgentDefinition::normalizedName))
        .toList();
  }

  public static Optional<SpecializedReviewAgentDefinition> findByName(String name) {
    String normalizedName = SpecializedReviewAgentDefinition.normalizeName(name);
    return load().stream()
        .filter(definition -> definition.normalizedName().equals(normalizedName))
        .findFirst();
  }

  public static String triageAgentList() {
    return load().stream()
        .map(
            definition ->
                definition.normalizedName() + ": " + definition.getShortDescription().strip())
        .reduce((left, right) -> left + "\n" + right)
        .orElse("");
  }

  public static String agentNames() {
    return load().stream()
        .map(SpecializedReviewAgentDefinition::normalizedName)
        .reduce((left, right) -> left + ", " + right)
        .orElse("");
  }

  private static void addDefinition(
      Map<String, SpecializedReviewAgentDefinition> definitions,
      String resourceName,
      InputStream inputStream) {
    if (inputStream == null) {
      return;
    }
    SpecializedReviewAgentDefinition definition =
        getGson()
            .fromJson(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8),
                SpecializedReviewAgentDefinition.class);
    if (definition == null || !definition.isValid()) {
      log.debug("Ignoring non-agent specialized review resource {}", resourceName);
      return;
    }
    definitions.put(definition.normalizedName(), definition);
  }
}
