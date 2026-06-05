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

package com.googlesource.gerrit.plugins.reviewai.localization;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class SystemMessageFormatter {
  private static final String PLUGIN_MESSAGE_PREFIX_KEY = "plugin.message.prefix";
  private static final String NORMAL_MESSAGE_LABEL_KEY = "plugin.message.label";
  private static final String WARNING_MESSAGE_LABEL_KEY = "plugin.warning.label";
  private static final String ERROR_MESSAGE_LABEL_KEY = "plugin.error.label";
  private static final String UNKNOWN_ENUM_CONFIG_WARNING_KEY =
      "message.config.unknown.enum.warning";

  private SystemMessageFormatter() {}

  public static String getLocalizedMessage(Localizer localizer, String messageKey, Object... args) {
    return format(localizer.getText(messageKey), args);
  }

  public static String getLocalizedErrorMessage(
      Localizer localizer, String messageKey, Object... args) {
    return getPrefixedErrorMessage(localizer, getLocalizedMessage(localizer, messageKey, args));
  }

  public static String getLocalizedWarningMessage(
      Localizer localizer, String messageKey, Object... args) {
    return getPrefixedWarningMessage(localizer, getLocalizedMessage(localizer, messageKey, args));
  }

  public static String getPrefixedSystemMessage(Localizer localizer, String message) {
    return getPrefixedMessage(localizer, NORMAL_MESSAGE_LABEL_KEY, message);
  }

  public static String getPrefixedWarningMessage(Localizer localizer, String message) {
    return getPrefixedMessage(localizer, WARNING_MESSAGE_LABEL_KEY, message);
  }

  public static String getPrefixedErrorMessage(Localizer localizer, String message) {
    return getPrefixedMessage(localizer, ERROR_MESSAGE_LABEL_KEY, message);
  }

  public static boolean isSystemMessage(Localizer localizer, String message) {
    if (message == null) {
      return false;
    }
    String strippedMessage = message.stripLeading();
    return getMessagePrefixes(localizer).stream().anyMatch(strippedMessage::startsWith);
  }

  public static String getMessagePrefixPattern(Localizer localizer) {
    return String.join(
        "|",
        getMessagePrefixes(localizer).stream()
            .map(Pattern::quote)
            .toList());
  }

  public static void appendConfigurationWarningMessages(
      Configuration config, Localizer localizer, List<String> messages) {
    messages.addAll(getConfigurationWarningMessages(config, localizer));
  }

  public static List<String> getConfigurationWarningMessages(
      Configuration config, Localizer localizer) {
    Set<String> unknownEnumSettings = config.getUnknownEnumSettings();
    if (unknownEnumSettings.isEmpty()) {
      return List.of();
    }
    return unknownEnumSettings.stream()
        .map(
            key ->
                getLocalizedWarningMessage(
                    localizer, UNKNOWN_ENUM_CONFIG_WARNING_KEY, key))
        .toList();
  }

  private static String getPrefixedMessage(Localizer localizer, String labelKey, String message) {
    if (message == null) {
      return null;
    }
    String prefix = getMessagePrefix(localizer, labelKey);
    if (prefix.isEmpty() || isSystemMessage(localizer, message)) {
      return message;
    }
    return prefix + ' ' + message;
  }

  private static List<String> getMessagePrefixes(Localizer localizer) {
    List<String> prefixes = new ArrayList<>();
    prefixes.add(getMessagePrefix(localizer, NORMAL_MESSAGE_LABEL_KEY));
    prefixes.add(getMessagePrefix(localizer, WARNING_MESSAGE_LABEL_KEY));
    prefixes.add(getMessagePrefix(localizer, ERROR_MESSAGE_LABEL_KEY));
    return prefixes.stream().filter(prefix -> !prefix.isEmpty()).toList();
  }

  private static String getMessagePrefix(Localizer localizer, String labelKey) {
    String pluginPrefix =
        Optional.ofNullable(localizer.getText(PLUGIN_MESSAGE_PREFIX_KEY)).orElse("").trim();
    String label = Optional.ofNullable(localizer.getText(labelKey)).orElse("").trim();
    if (pluginPrefix.isEmpty() || label.isEmpty()) {
      return "";
    }
    return pluginPrefix + ' ' + label + ':';
  }

  private static String format(String message, Object... args) {
    if (args == null || args.length == 0) {
      return message;
    }
    return String.format(message, args);
  }
}
