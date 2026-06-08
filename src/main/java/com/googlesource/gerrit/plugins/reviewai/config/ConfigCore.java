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

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.googlesource.gerrit.plugins.reviewai.utils.StringUtils;
import com.googlesource.gerrit.plugins.reviewai.utils.TextUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

import static com.googlesource.gerrit.plugins.reviewai.utils.CollectionUtils.arrayToList;
import static com.googlesource.gerrit.plugins.reviewai.utils.StringUtils.*;

@Slf4j
public abstract class ConfigCore {
  private static final Set<String> EXCLUDE_FROM_DUMP = Set.of("KEY_AI_TOKENS");
  private static final String GLOBAL_CONFIG = "GlobalConfig";
  private static final String PROJECT_CONFIG = "ProjectConfig";

  public static final String NOT_CONFIGURED_ERROR_MSG = "%s is not configured";
  // The convention `KEY_<CONFIG_KEY> = "configKey"` is used for naming config key constants
  public static final String PREFIX_KEY = "KEY_";
  // The convention "getConfigKey"` is used for naming config key getter methods
  public static final String PREFIX_GETTER = "get";

  protected final OneOffRequestContext context;
  @Getter protected final Account.Id userId;
  @Getter protected final PluginConfig globalConfig;
  @Getter protected final PluginConfig projectConfig;
  @Getter protected final String gerritUserEmail;
  @Getter protected final GerritApi gerritApi;

  private final LinkedHashMap<String, PluginConfig> configScopes;

  private boolean isDumpingConfig = false;
  @Getter private Set<String> unknownEnumSettings = new HashSet<>();

  public ConfigCore(
      OneOffRequestContext context,
      GerritApi gerritApi,
      PluginConfig globalConfig,
      PluginConfig projectConfig,
      String gerritUserEmail,
      Account.Id userId) {
    this.context = context;
    this.gerritApi = gerritApi;
    this.globalConfig = globalConfig;
    this.projectConfig = projectConfig;
    this.gerritUserEmail = gerritUserEmail;
    this.userId = userId;

    configScopes = new LinkedHashMap<>();
    configScopes.put(GLOBAL_CONFIG, globalConfig);
    configScopes.put(PROJECT_CONFIG, projectConfig);
  }

  public ManualRequestContext openRequestContext() {
    return context.openAs(userId);
  }

  public String getString(String key, String defaultValue) {
    String value = projectConfig.getString(key);
    if (value != null) {
      return value;
    }
    return globalConfig.getString(key, defaultValue);
  }

  public String getString(String key) {
    // Use getString(key, "") instead of getString(key) to avoid inconsistencies in mock behavior.
    return getString(key, "");
  }

  public Locale getLocaleDefault() {
    return Locale.getDefault();
  }

  protected boolean isDefinedKey(Class<?> configClass, String key) {
    try {
      String configKey = PREFIX_KEY + convertCamelToSnakeCase(key).toUpperCase();
      log.debug("Checking if config key `{}` for {} is defined", configKey, key);
      Field field = configClass.getDeclaredField(configKey);
      String value = getFieldConfigValue(field);
      log.debug("Config key value: {}", value);
      return value.equals(key);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      log.debug("Error checking if config key `{}` is defined", key, e);
      return false;
    }
  }

  protected TreeMap<String, String> dumpConfigMap(Class<?> configClass) {
    log.debug("Start dumping config map");
    TreeMap<String, String> configMap = new TreeMap<>();
    isDumpingConfig = true;
    try {
      for (Field field : configClass.getDeclaredFields()) {
        String fieldName = field.getName();
        log.debug("Processing dumping config field `{}`", fieldName);
        if (!fieldName.startsWith(PREFIX_KEY) || EXCLUDE_FROM_DUMP.contains(fieldName)) {
          continue;
        }
        String fieldValue = getFieldConfigValue(field);
        String getterName = PREFIX_GETTER + capitalizeFirstLetter(fieldValue);
        String configValue;
        try {
          configValue = configClass.getDeclaredMethod(getterName).invoke(this).toString();
        } catch (NoSuchMethodException e) {
          log.debug(
              "Config field `{}` lacking getter method `{}` is excluded from the dump",
              fieldName,
              getterName);
          continue;
        }
        log.debug(
            "Config entities retrieved - Field Value: `{}`, Getter Name: `{}`, Config "
                + "Value: `{}`",
            fieldValue,
            getterName,
            configValue);
        configMap.put(fieldValue, configValue);
      }
      return configMap;
    } catch (InvocationTargetException | IllegalAccessException e) {
      log.warn("Error retrieving configuration", e);
      return null;
    } finally {
      isDumpingConfig = false;
    }
  }

  protected String getValidatedOrThrow(String key) {
    String value = projectConfig.getString(key);
    if (value == null) {
      value = globalConfig.getString(key);
    }
    if (value == null) {
      throw new RuntimeException(String.format(NOT_CONFIGURED_ERROR_MSG, key));
    }
    return value;
  }

  protected int getInt(String key, int defaultValue) {
    int valueForProject = projectConfig.getInt(key, defaultValue);
    // To avoid misinterpreting an undefined value as zero, a secondary check is performed by
    // retrieving the value
    // as a String.
    if (valueForProject != defaultValue
        && valueForProject != 0
        && projectConfig.getString(key, "") != null) {
      return valueForProject;
    }
    return globalConfig.getInt(key, defaultValue);
  }

  protected boolean getBoolean(String key, boolean defaultValue) {
    boolean valueForProject = projectConfig.getBoolean(key, defaultValue);
    if (projectConfig.getString(key) != null) {
      return valueForProject;
    }
    return globalConfig.getBoolean(key, defaultValue);
  }

  protected Double getDouble(String key, Double defaultValue) {
    return Double.parseDouble(getString(key, String.valueOf(defaultValue)));
  }

  protected <T extends Enum<T>> T getEnum(String key, String defaultValue, Class<T> enumClass) {
    String value = getString(key, defaultValue);
    try {
      return Enum.valueOf(enumClass, value);
    } catch (IllegalArgumentException e) {
      unknownEnumSettings.add(key);
      return Enum.valueOf(enumClass, defaultValue);
    }
  }

  public void clearUnknownEnumSetting(String key) {
    unknownEnumSettings.remove(key);
  }

  protected List<String> splitConfig(String value) {
    return value.isEmpty() ? List.of() : TextUtils.splitString(value);
  }

  protected List<String> splitConfigRemoveDots(String value) {
    return splitConfig(value).stream().map(s -> s.replaceAll("^\\.", "")).toList();
  }

  protected List<String> splitListIntoItems(String key, List<String> defaultValue) {
    log.debug("Retrieving and splitting Global and Project configuration items for key {}", key);
    return splitListIntoItems(key, defaultValue, configScopes.values().stream().toList());
  }

  protected List<String> splitListIntoItemsWithProjectOverride(
      String key, List<String> defaultValue) {
    log.debug(
        "Retrieving and splitting Project or Global configuration items for key {}", key);
    List<PluginConfig> scopes =
        projectConfig.getString(key) == null
            ? List.of(globalConfig)
            : List.of(projectConfig);
    return splitListIntoItems(key, defaultValue, scopes);
  }

  private List<String> splitListIntoItems(
      String key, List<String> defaultValue, List<PluginConfig> scopes) {
    List<String> items = new ArrayList<>();
    for (PluginConfig configScope : scopes) {
      List<String> scopeItems = arrayToList(configScope.getStringList(key));
      items.addAll(scopeItems);
      log.debug("Configuration split items retrieved: {}", scopeItems);
    }

    log.debug("Configuration split items: {}", items);
    if (items.isEmpty()) {
      return defaultValue;
    }
    try {
      items.replaceAll(StringUtils::backslashDoubleQuotes);
    } catch (NullPointerException e) {
      log.warn(
          "Unable to retrieve Global and/or Project configuration items (possible issue with unescaped "
              + "double quotes): {}",
          key,
          e);
      return defaultValue;
    }
    log.debug("Sanitized configuration split items: {}", items);

    if (isDumpingConfig) {
      items.replaceAll(TextUtils::wrapQuotes);
      log.debug("Wrapped items with double quotes: {}", items);
    }
    return items;
  }

  private String getFieldConfigValue(Field field) throws IllegalAccessException {
    field.setAccessible(true);
    return field.get(null).toString();
  }
}
