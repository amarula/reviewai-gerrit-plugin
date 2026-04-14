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
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.project.NoSuchProjectException;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerBaseProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.config.entry.IConfigEntry;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Config;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static com.googlesource.gerrit.plugins.reviewai.config.entry.ConfigEntryFactory.getConfigEntry;
import static com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManager.KEY_DYNAMIC_CONFIG;
import static com.googlesource.gerrit.plugins.reviewai.utils.CollectionUtils.putAllMergeStringLists;

@Singleton
@Slf4j
public class ConfigCreator {
  private final String pluginName;

  private final AccountCache accountCache;
  private final PluginConfigFactory configFactory;

  private final OneOffRequestContext context;
  private final GerritApi gerritApi;
  private final PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider;

  @Inject
  ConfigCreator(
      @PluginName String pluginName,
      AccountCache accountCache,
      PluginConfigFactory configFactory,
      OneOffRequestContext context,
      GerritApi gerritApi,
      PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider) {
    this.pluginName = pluginName;
    this.accountCache = accountCache;
    this.configFactory = configFactory;
    this.context = context;
    this.gerritApi = gerritApi;
    this.pluginDataHandlerBaseProvider = pluginDataHandlerBaseProvider;
    log.debug("ConfigCreator initialized for plugin: {}", pluginName);
  }

  public Configuration createConfig(Project.NameKey projectName, Change.Key changeKey)
      throws NoSuchProjectException {
    log.debug("Creating configuration for project: {} and change: {}", projectName, changeKey);
    PluginConfig globalConfig = configFactory.getFromGerritConfig(pluginName);
    log.debug("Global configuration loaded with items: {}", globalConfig.getNames());
    PluginConfig projectConfig = configFactory.getFromProjectConfig(projectName, pluginName);
    log.debug("Project configuration loaded with items: {}", projectConfig.getNames());
    // `PluginDataHandlerProvider` cannot be injected because `GerritChange` is not initialized at
    // this stage:
    // instead of using `PluginDataHandlerProvider.getChangeScope`,
    // `PluginDataHandlerBaseProvider.get` is employed
    Map<String, String> dynamicConfig =
        pluginDataHandlerBaseProvider
            .get(changeKey.toString())
            .getJsonObjectValue(KEY_DYNAMIC_CONFIG, String.class);
    if (dynamicConfig != null && !dynamicConfig.isEmpty()) {
      log.debug("DynamicConfig found for change '{}': {}", changeKey, dynamicConfig);
      projectConfig = updateDynamicConfig(projectConfig, pluginName, dynamicConfig);
    }
    Optional<AccountState> aiAccount = getAccount(globalConfig);
    String email = aiAccount.map(a -> a.account().preferredEmail()).orElse("");
    Account.Id accountId =
        aiAccount
            .map(a -> a.account().id())
            .orElseThrow(
                () ->
                    new RuntimeException(
                        String.format(
                            "Given account %s doesn't exist",
                            globalConfig.getString(Configuration.KEY_GERRIT_USERNAME))));
    return new Configuration(context, gerritApi, globalConfig, projectConfig, email, accountId);
  }

  private Optional<AccountState> getAccount(PluginConfig globalConfig) {
    String aiUser = globalConfig.getString(Configuration.KEY_GERRIT_USERNAME);
    log.debug("Retrieving account for username: {}", aiUser);
    return accountCache.getByUsername(aiUser);
  }

  private PluginConfig updateDynamicConfig(
      PluginConfig projectConfig, String pluginName, Map<String, String> dynamicConfig) {
    log.debug("Updating dynamic configuration for plugin: {}", pluginName);
    // Retrieve all current configuration values
    Map<String, Object> currentConfigValues = new HashMap<>();
    Map<String, Object> dynamicConfigValues = new HashMap<>();
    for (String key : projectConfig.getNames()) {
      IConfigEntry configEntry = getConfigEntry(key);
      configEntry.setCurrentConfigValue(currentConfigValues, projectConfig);
    }
    for (Map.Entry<String, String> dynamicEntry : dynamicConfig.entrySet()) {
      IConfigEntry configEntry = getConfigEntry(dynamicEntry.getKey());
      configEntry.setDynamicConfigValue(dynamicConfigValues, dynamicEntry.getValue());
    }
    // Merge current config with new values from dynamicConfig
    putAllMergeStringLists(currentConfigValues, dynamicConfigValues);
    PluginConfig.Update configUpdater = getProjectUpdate(pluginName, currentConfigValues);

    return configUpdater.asPluginConfig();
  }

  private PluginConfig.@NonNull Update getProjectUpdate(
      String pluginName, Map<String, Object> currentConfigValues) {
    log.debug("Applying merged configuration for plugin: {}", pluginName);
    PluginConfig.Update configUpdater =
        new PluginConfig.Update(pluginName, new Config(), Optional.empty());
    // Set all merged values
    for (Map.Entry<String, Object> entry : currentConfigValues.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();
      log.debug("Assigning merged values for key {}: {}", key, value);

      IConfigEntry configEntry = getConfigEntry(key);
      configEntry.setMergedConfigValue(configUpdater, value);
    }
    return configUpdater;
  }
}
