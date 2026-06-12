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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.commands.IPatchSetProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.utils.TextUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.jsonArrayToList;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.distanceCodeDelimiter;

@Slf4j
public class ClientCommandParser extends ClientCommandBase {
  private static final Map<String, BaseOptionSet> BASE_OPTION_MAP =
      Map.of(
          "filter", BaseOptionSet.FILTER,
          "debug", BaseOptionSet.DEBUG,
          "scope", BaseOptionSet.SCOPE,
          "reset", BaseOptionSet.RESET,
          "remove", BaseOptionSet.REMOVE,
          "config", BaseOptionSet.CONFIG,
          "local_data", BaseOptionSet.LOCAL_DATA,
          "prompts", BaseOptionSet.PROMPTS,
          "instructions", BaseOptionSet.INSTRUCTIONS);
  private static final Map<CommandSet, List<BaseOptionSet>> COMMAND_VALID_OPTIONS_MAP =
      Map.of(
          CommandSet.REVIEW,
              List.of(BaseOptionSet.FILTER, BaseOptionSet.DEBUG, BaseOptionSet.SCOPE),
          CommandSet.SUGGEST, List.of(BaseOptionSet.SCOPE),
          CommandSet.CONFIGURE, List.of(BaseOptionSet.RESET, BaseOptionSet.CONFIGURATION_OPTION),
          CommandSet.DIRECTIVES, List.of(BaseOptionSet.RESET, BaseOptionSet.REMOVE),
          CommandSet.SHOW,
              List.of(
                  BaseOptionSet.CONFIG,
                  BaseOptionSet.LOCAL_DATA,
                  BaseOptionSet.PROMPTS,
                  BaseOptionSet.INSTRUCTIONS,
                  BaseOptionSet.SCOPE));
  private static final List<CommandSet> REVIEW_COMMANDS =
      new ArrayList<>(List.of(CommandSet.REVIEW, CommandSet.SUGGEST));
  private static final List<CommandSet> BASE_OPTIONS_REQUIRED =
      new ArrayList<>(List.of(CommandSet.SHOW));
  private static final List<CommandSet> DEBUG_REQUIRED_COMMANDS =
      new ArrayList<>(
          List.of(
              CommandSet.DIRECTIVES,
              CommandSet.CONFIGURE,
              CommandSet.SHOW));

  private final ChangeSetData changeSetData;
  private final Localizer localizer;
  private final ClientCommandExecutor clientCommandExecutor;

  private Map<BaseOptionSet, String> baseOptions;
  private Map<String, String> dynamicOptions;

  public ClientCommandParser(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      IPatchSetProvider IPatchSetProvider,
      PluginChatMemoryStore chatMemoryStore) {
    super(config);
    this.localizer = localizer;
    this.changeSetData = changeSetData;
    this.clientCommandExecutor =
        new ClientCommandExecutor(
            config,
            changeSetData,
            change,
            codeContextPolicy,
            pluginDataHandlerProvider,
            localizer,
            IPatchSetProvider,
            chatMemoryStore);
    log.debug("ClientCommandParser initialized.");
  }

  public boolean parseCommands(String comment) {
    return parseCommands(comment, true);
  }

  public boolean parseCommands(String comment, boolean executeCommands) {
    boolean commandFound = false;
    log.debug("Parsing commands from comment: {}", comment);
    changeSetData.setShowDynamicConfigMessage(false);
    changeSetData.clearParsedCommands();
    if (parseMessageCommand(comment)) {
      log.debug("Message command detected: parsing complete.");
      return false;
    }
    Matcher commandMatcher = COMMAND_PATTERN.matcher(comment);
    changeSetData.setHideAiReview(true);
    while (commandMatcher.find()) {
      log.debug("Parsing command: {} - Parsing args: {}", commandMatcher.group(1), commandMatcher.group(2));
      CommandSet command = COMMAND_MAP.get(commandMatcher.group(1));
      if (command != null) {
        changeSetData.setShowDynamicConfigMessage(
            DYNAMIC_CONFIG_MESSAGE_COMMANDS.contains(command));
      }
      if (!parseSingleCommand(comment, commandMatcher, command, executeCommands)) {
        return false;
      }
      commandFound = true;
      if (command == CommandSet.HELP) {
        break;
      }
    }
    if (!changeSetData.getForcedReview()) {
      changeSetData.setHideAiReview(false);
    }
    return commandFound;
  }

  private boolean parseMessageCommand(String comment) {
    Matcher messageCommandMatcher = MESSAGE_COMMAND_PATTERN.matcher(comment);
    return messageCommandMatcher.find();
  }

  private boolean parseSingleCommand(
      String comment, Matcher commandMatcher, CommandSet command, boolean executeCommands) {
    baseOptions = new HashMap<>();
    dynamicOptions = new HashMap<>();
    if (command == null) {
      changeSetData.setReviewSystemMessage(
          String.format(
              localizer.getText("message.command.unknown"), distanceCodeDelimiter(comment)));
      log.info("Unknown command in comment `{}`", comment);
      return false;
    }
    parseOptions(commandMatcher);
    changeSetData.addParsedCommand(commandMatcher.group(1), getParsedOptions());
    if (validateCommand(command)) {
      if (executeCommands) {
        clientCommandExecutor.executeCommand(
            command, baseOptions, dynamicOptions, comment.substring(commandMatcher.end()));
        clientCommandExecutor.postExecuteCommand();
      }
    } else {
      log.info("Command in comment `{}` not validated", comment);
    }
    return true;
  }

  private boolean validateCommand(CommandSet command) {
    log.debug("Validating command: {}", command);
    if (optionsMismatch(command)) {
      return false;
    }
    if (!config.getEnableMessageDebugging() && requiresMessageDebugging(command)) {
      changeSetData.setReviewSystemMessage(
          localizer.getText("message.command.debugging.messages.disabled"));
      log.debug(
          "Command `{}` not validated: `enableMessageDebugging` config must be set to true",
          command);
      return false;
    }
    log.debug("Command `{}` validated", command);
    return true;
  }

  private boolean requiresMessageDebugging(CommandSet command) {
    return DEBUG_REQUIRED_COMMANDS.contains(command)
        || REVIEW_COMMANDS.contains(command) && baseOptions.containsKey(BaseOptionSet.DEBUG);
  }

  private boolean optionsMismatch(CommandSet command) {
    log.debug("Validating options for command: {}", command);
    List<BaseOptionSet> commandOptions = COMMAND_VALID_OPTIONS_MAP.get(command);
    if (baseOptions.isEmpty()) {
      if (BASE_OPTIONS_REQUIRED.contains(command) && dynamicOptions.isEmpty()) {
        log.debug("Option(s) required for command `{}`", command);
        changeSetData.setReviewSystemMessage(
            SystemMessageFormatter.getLocalizedWarningMessage(
                localizer, "message.command.option.required", command));
        return true;
      }
    } else if (commandOptions == null
        || !(new HashSet<>(commandOptions).containsAll(baseOptions.keySet()))) {
      log.debug("Invalid option for command `{}`: {}", command, baseOptions);
      changeSetData.setReviewSystemMessage(
          SystemMessageFormatter.getLocalizedWarningMessage(
              localizer, "message.command.option.invalid", command, baseOptions));
      return true;
    }
    if (showScopeOptionMismatch(command)) {
      return true;
    }
    if (baseOptionValuesMismatch(command)) {
      return true;
    }
    if (!dynamicOptions.isEmpty()) {
      if (commandOptions == null || !commandOptions.contains(BaseOptionSet.CONFIGURATION_OPTION)) {
        log.debug("Unknown option(s) for command `{}`: {}", command, dynamicOptions);
        changeSetData.setReviewSystemMessage(
            SystemMessageFormatter.getLocalizedWarningMessage(
                localizer, "message.command.option.unknown", command, dynamicOptions));
        return true;
      }
      return configurationOptionsMismatch();
    }
    return false;
  }

  private boolean showScopeOptionMismatch(CommandSet command) {
    if (command != CommandSet.SHOW || !baseOptions.containsKey(BaseOptionSet.SCOPE)) {
      return false;
    }
    if (baseOptions.containsKey(BaseOptionSet.PROMPTS)
        || baseOptions.containsKey(BaseOptionSet.INSTRUCTIONS)) {
      return false;
    }
    changeSetData.setReviewSystemMessage(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer, "message.command.option.invalid", command, baseOptions));
    return true;
  }

  private boolean baseOptionValuesMismatch(CommandSet command) {
    for (Map.Entry<BaseOptionSet, String> baseOption : baseOptions.entrySet()) {
      List<String> validValues = getValidBaseOptionValues(command, baseOption.getKey());
      if (validValues != null && !validValues.contains(baseOption.getValue())) {
        changeSetData.setReviewSystemMessage(
            SystemMessageFormatter.getLocalizedWarningMessage(
                localizer,
                "message.command.option.value.invalid",
                baseOption.getKey(),
                baseOption.getValue(),
                validValues));
        log.debug(
            "Invalid value for option `{}`: {}. Valid values are: {}",
            baseOption.getKey(),
            baseOption.getValue(),
            validValues);
        return true;
      }
    }
    return false;
  }

  private List<String> getValidBaseOptionValues(CommandSet command, BaseOptionSet option) {
    if (option != BaseOptionSet.SCOPE) {
      return null;
    }
    return command == CommandSet.REVIEW || command == CommandSet.SUGGEST
        ? ReviewScope.reviewCommandOptionValues()
        : ReviewScope.commandOptionValues();
  }

  private boolean configurationOptionsMismatch() {
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
      if (baseOptions.containsKey(BaseOptionSet.RESET) && dynamicEntry.getValue().isEmpty()) {
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

  private void parseOptions(Matcher commandMatcher) {
    log.debug("Parsing options `{}`", commandMatcher.group(2));
    if (commandMatcher.group(2) == null) return;
    Matcher reviewOptionsMatcher = OPTIONS_PATTERN.matcher(commandMatcher.group(2));
    while (reviewOptionsMatcher.find()) {
      parseSingleOption(reviewOptionsMatcher);
    }
  }

  private void parseSingleOption(Matcher reviewOptionsMatcher) {
    String optionKey = reviewOptionsMatcher.group(1);
    String optionValue =
        Optional.ofNullable(reviewOptionsMatcher.group(2))
            .map(TextUtils::unwrapDeSlashQuotes)
            .orElse("");
    log.debug("Parsed option - Key: {} - Value: {}", optionKey, optionValue);
    if (BASE_OPTION_MAP.containsKey(optionKey)) {
      baseOptions.put(BASE_OPTION_MAP.get(optionKey), optionValue);
    } else {
      dynamicOptions.put(optionKey, optionValue);
    }
  }

  private Map<String, String> getParsedOptions() {
    Map<String, String> parsedOptions = new HashMap<>();
    for (Map.Entry<BaseOptionSet, String> baseOption : baseOptions.entrySet()) {
      parsedOptions.put(baseOption.getKey().name(), baseOption.getValue());
    }
    parsedOptions.putAll(dynamicOptions);
    return parsedOptions;
  }
}
