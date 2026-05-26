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

package com.googlesource.gerrit.plugins.reviewai.config.dynamic;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class DynamicConfigManager {
  public static final String KEY_DYNAMIC_CONFIG = "dynamicConfig";
  public static final String KEY_SELECTED_AI_MODEL = "selectedAiModel";

  private final PluginDataHandler pluginDataHandler;
  private final Map<String, String> dynamicConfig;

  public DynamicConfigManager(PluginDataHandlerProvider pluginDataHandlerProvider) {
    this.pluginDataHandler = pluginDataHandlerProvider.getChangeScope();
    dynamicConfig =
        Optional.ofNullable(pluginDataHandler.getJsonObjectValue(KEY_DYNAMIC_CONFIG, String.class))
            .orElse(new HashMap<>());
    log.debug("Loaded dynamic configuration: {}", dynamicConfig);
  }

  public String getConfig(String key) {
    log.debug("Retrieving config key: {}", key);
    return dynamicConfig.get(key);
  }

  public Map<String, String> getDynamicConfigForDisplay(Configuration config) {
    if (dynamicConfig == null || dynamicConfig.isEmpty()) {
      return Map.of();
    }
    if (dynamicConfig.size() == 1
        && isDefaultSelectedAiModel(config, dynamicConfig.get(KEY_SELECTED_AI_MODEL))) {
      return Map.of();
    }
    return dynamicConfig;
  }

  public void setConfig(String key, String value) {
    log.debug("Setting config key: {} with value: {}", key, value);
    dynamicConfig.put(key, value);
  }

  public void updateConfiguration(boolean modifiedDynamicConfig, boolean shouldResetDynamicConfig) {
    if (dynamicConfig == null || dynamicConfig.isEmpty()) {
      log.debug("Dynamic configuration is empty or null, skipping update.");
      return;
    }
    if (shouldResetDynamicConfig && !modifiedDynamicConfig) {
      log.debug("Resetting dynamic configuration without modification.");
      pluginDataHandler.removeValue(KEY_DYNAMIC_CONFIG);
    } else {
      if (shouldResetDynamicConfig) {
        log.debug("Resetting dynamic configuration.");
        resetDynamicConfig();
      }
      log.info("Updating dynamic configuration with {}", dynamicConfig);
      pluginDataHandler.setJsonValue(KEY_DYNAMIC_CONFIG, dynamicConfig);
    }
  }

  public static boolean isDefaultSelectedAiModel(Configuration config, String selectedAiModel) {
    if (selectedAiModel == null || selectedAiModel.isBlank()) {
      return false;
    }
    return selectedAiModel.trim().equals(getDefaultAiModel(config));
  }

  private static String getDefaultAiModel(Configuration config) {
    if (config == null) {
      return "";
    }
    List<String> aiModels = config.getAiModels();
    if (aiModels == null || aiModels.isEmpty()) {
      return "";
    }
    int zeroBasedIndex = config.getAiModelsDefaultIndex() - 1;
    if (zeroBasedIndex >= 0 && zeroBasedIndex < aiModels.size()) {
      return aiModels.get(zeroBasedIndex);
    }
    return aiModels.get(0);
  }

  private void resetDynamicConfig() {
    // The keys with empty values are simply removed
    log.debug("Resetting dynamic configuration by removing empty or null values.");
    dynamicConfig
        .entrySet()
        .removeIf(entry -> entry.getValue() == null || entry.getValue().isEmpty());
    log.debug("Dynamic configuration after reset: {}", dynamicConfig);
  }
}
