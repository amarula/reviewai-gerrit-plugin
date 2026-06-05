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
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class SystemMessageFormatter {
  private static final String SYSTEM_MESSAGE_PREFIX_KEY = "system.message.prefix";
  private static final String WARNING_MESSAGE_PREFIX_KEY = "warning.message.prefix";
  private static final String ERROR_MESSAGE_PREFIX_KEY = "error.message.prefix";
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
    return getPrefixedMessage(localizer, SYSTEM_MESSAGE_PREFIX_KEY, message);
  }

  public static String getPrefixedWarningMessage(Localizer localizer, String message) {
    return getPrefixedMessage(localizer, WARNING_MESSAGE_PREFIX_KEY, message);
  }

  public static String getPrefixedErrorMessage(Localizer localizer, String message) {
    return getPrefixedMessage(localizer, ERROR_MESSAGE_PREFIX_KEY, message);
  }

  public static boolean isSystemMessage(Localizer localizer, String message) {
    if (message == null) {
      return false;
    }
    String prefix = getPrefix(localizer, SYSTEM_MESSAGE_PREFIX_KEY);
    return !prefix.isEmpty() && message.stripLeading().startsWith(prefix);
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

  private static String getPrefixedMessage(Localizer localizer, String prefixKey, String message) {
    if (message == null) {
      return null;
    }
    String prefix = getPrefix(localizer, prefixKey);
    if (prefix.isEmpty() || message.stripLeading().startsWith(prefix)) {
      return message;
    }
    return prefix + ' ' + message;
  }

  private static String getPrefix(Localizer localizer, String prefixKey) {
    return Optional.ofNullable(localizer.getText(prefixKey)).orElse("").trim();
  }

  private static String format(String message, Object... args) {
    if (args == null || args.length == 0) {
      return message;
    }
    return String.format(message, args);
  }
}
