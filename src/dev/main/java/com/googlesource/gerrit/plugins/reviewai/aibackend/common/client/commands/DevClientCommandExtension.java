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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.jsonArrayToList;

import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksDirectives;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksDynamicConfiguration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManager;
import com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManagerDirectives;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.DynamicDirectivesModifyException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.commands.IPatchSetProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class DevClientCommandExtension implements ClientCommandExtension {
  @Override
  public boolean requiresAdministrator(
      ClientCommandBase.CommandSet command,
      Map<ClientCommandBase.BaseOptionSet, String> baseOptions) {
    return command == ClientCommandBase.CommandSet.DIRECTIVES
        || command == ClientCommandBase.CommandSet.CONFIGURE
        || command == ClientCommandBase.CommandSet.SHOW
        || command == ClientCommandBase.CommandSet.REVIEW
            && baseOptions.containsKey(ClientCommandBase.BaseOptionSet.DEBUG);
  }

  @Override
  public boolean acceptsDynamicOptions(ClientCommandBase.CommandSet command) {
    return command == ClientCommandBase.CommandSet.CONFIGURE;
  }

  @Override
  public boolean dynamicOptionsMismatch(
      Configuration config,
      ChangeSetData changeSetData,
      Localizer localizer,
      Map<ClientCommandBase.BaseOptionSet, String> baseOptions,
      Map<String, String> dynamicOptions) {
    log.debug("Checking for mismatches in configuration options");
    for (Map.Entry<String, String> dynamicEntry : dynamicOptions.entrySet()) {
      String key = dynamicEntry.getKey();
      if (!config.isDefinedKey(key)) {
        log.debug("Unknown configuration option: {}", key);
        changeSetData.setReviewSystemMessage(
            SystemMessageFormatter.getLocalizedWarningMessage(
                localizer, "message.command.option.config.unknown", key));
        return true;
      }
      if (baseOptions.containsKey(ClientCommandBase.BaseOptionSet.RESET)
          && dynamicEntry.getValue().isEmpty()) {
        continue;
      }
      Optional<List<String>> validValues = config.getValidDynamicConfigValues(key);
      if (validValues.isPresent() && !validValues.get().contains(dynamicEntry.getValue())) {
        changeSetData.setReviewSystemMessage(
            SystemMessageFormatter.getLocalizedWarningMessage(
                localizer,
                "message.command.option.value.invalid",
                key,
                dynamicEntry.getValue(),
                validValues.get()));
        log.debug(
            "Invalid value for configuration option `{}`: {}. Valid values are: {}",
            key,
            dynamicEntry.getValue(),
            validValues.get());
        return true;
      }
      validValues.ifPresent(values -> config.clearUnknownEnumSetting(key));
      if (Configuration.LIST_TYPE_ENTRY_KEYS.contains(key)
          && jsonArrayToList(dynamicEntry.getValue()).isEmpty()) {
        log.debug("Value of `{}` must be formatted as a JSON array", key);
        changeSetData.setReviewSystemMessage(
            SystemMessageFormatter.getLocalizedWarningMessage(
                localizer, "message.command.option.config.array.malformed", key));
        return true;
      }
    }
    return false;
  }

  @Override
  public boolean applyReviewDebugOption(
      ChangeSetData changeSetData, Map<ClientCommandBase.BaseOptionSet, String> baseOptions) {
    if (!baseOptions.containsKey(ClientCommandBase.BaseOptionSet.DEBUG)) {
      return false;
    }
    log.debug("Response Mode set to Debug");
    changeSetData.setDebugReviewMode(true);
    changeSetData.setReplyFilterEnabled(false);
    return true;
  }

  @Override
  public boolean executeCommand(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      IPatchSetProvider patchSetProvider,
      ClientCommandBase.CommandSet command,
      Map<ClientCommandBase.BaseOptionSet, String> baseOptions,
      Map<String, String> dynamicOptions,
      String nextString) {
    switch (command) {
      case CONFIGURE -> commandDynamicallyConfigure(
          config, changeSetData, pluginDataHandlerProvider, localizer, baseOptions, dynamicOptions);
      case DIRECTIVES -> commandDirectives(
          changeSetData, pluginDataHandlerProvider, localizer, baseOptions, nextString);
      case SHOW -> commandShow(
          config,
          changeSetData,
          change,
          codeContextPolicy,
          pluginDataHandlerProvider,
          localizer,
          patchSetProvider,
          baseOptions);
      default -> {
        return false;
      }
    }
    return true;
  }

  @Override
  public Optional<String> getDynamicConfigurationMessage(
      Configuration config,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer) {
    Map<String, String> dynamicConfig =
        new DynamicConfigManager(pluginDataHandlerProvider).getDynamicConfigForDisplay(config);
    if (dynamicConfig == null || dynamicConfig.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new DebugCodeBlocksDynamicConfiguration(localizer).getDebugCodeBlock(dynamicConfig));
  }

  private void commandDynamicallyConfigure(
      Configuration config,
      ChangeSetData changeSetData,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      Map<ClientCommandBase.BaseOptionSet, String> baseOptions,
      Map<String, String> dynamicOptions) {
    boolean modifiedDynamicConfig = false;
    boolean shouldResetDynamicConfig = false;
    DynamicConfigManager dynamicConfigManager = new DynamicConfigManager(pluginDataHandlerProvider);

    if (baseOptions.containsKey(ClientCommandBase.BaseOptionSet.RESET)) {
      shouldResetDynamicConfig = true;
      log.debug("Resetting configuration settings");
    }
    if (!dynamicOptions.isEmpty()) {
      modifiedDynamicConfig = true;
      for (Map.Entry<String, String> dynamicOption : dynamicOptions.entrySet()) {
        String optionKey = dynamicOption.getKey();
        String optionValue = dynamicOption.getValue();
        log.debug("Updating configuration setting '{}' to '{}'", optionKey, optionValue);
        dynamicConfigManager.setConfig(optionKey, optionValue);
      }
    }
    dynamicConfigManager.updateConfiguration(modifiedDynamicConfig, shouldResetDynamicConfig);
    changeSetData.setReviewSystemMessage(
        SystemMessageFormatter.getPrefixedSystemMessage(
            localizer, localizer.getText("message.dump.dynamic.configuration.notify")));
    Map<String, String> dynamicConfig = dynamicConfigManager.getDynamicConfigForDisplay(config);
    if (dynamicConfig != null && !dynamicConfig.isEmpty()) {
      changeSetData.setReviewStatusMessage(
          new DebugCodeBlocksDynamicConfiguration(localizer).getDebugCodeBlock(dynamicConfig));
    }
  }

  private void commandDirectives(
      ChangeSetData changeSetData,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      Map<ClientCommandBase.BaseOptionSet, String> baseOptions,
      String nextString) {
    DynamicConfigManagerDirectives dynamicConfigManagerDirectives =
        new DynamicConfigManagerDirectives(pluginDataHandlerProvider);
    DebugCodeBlocksDirectives debugCodeBlocksDirectives = new DebugCodeBlocksDirectives(localizer);
    try {
      if (baseOptions.containsKey(ClientCommandBase.BaseOptionSet.RESET)) {
        dynamicConfigManagerDirectives.resetDirectives();
      } else if (baseOptions.containsKey(ClientCommandBase.BaseOptionSet.REMOVE)) {
        dynamicConfigManagerDirectives.removeDirective(nextString);
      } else if (!nextString.isEmpty()) {
        dynamicConfigManagerDirectives.addDirective(nextString);
      }
    } catch (DynamicDirectivesModifyException e) {
      changeSetData.setReviewSystemMessage(
          SystemMessageFormatter.getLocalizedErrorMessage(
              localizer, "message.dump.directives.modify.error"));
      return;
    }
    changeSetData.setReviewSystemMessage(
        debugCodeBlocksDirectives.getDebugCodeBlock(
            dynamicConfigManagerDirectives.getDirectives()));
  }

  private void commandShow(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      IPatchSetProvider patchSetProvider,
      Map<ClientCommandBase.BaseOptionSet, String> baseOptions) {
    ClientCommandShowExecutor clientCommandShowExecutor =
        new ClientCommandShowExecutor(
            config,
            changeSetData,
            change,
            codeContextPolicy,
            pluginDataHandlerProvider,
            localizer,
            patchSetProvider);
    clientCommandShowExecutor.executeShowCommand(baseOptions);
  }
}
