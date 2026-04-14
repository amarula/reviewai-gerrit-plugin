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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.ClientBase;
import com.google.common.collect.ImmutableBiMap;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

@Slf4j
public abstract class ClientCommandBase extends ClientBase {
  public enum CommandSet {
    HELP,
    MESSAGE,
    REVIEW,
    REVIEW_LAST,
    DIRECTIVES,
    FORGET_THREAD,
    CONFIGURE,
    SHOW
  }

  public enum BaseOptionSet {
    FILTER,
    DEBUG,
    RESET,
    REMOVE,
    // `CONFIGURATION_OPTION` is a placeholder option indicating that the associated options must be
    // validated
    // against the Configuration keys.
    CONFIGURATION_OPTION,
    CONFIG,
    LOCAL_DATA,
    PROMPTS,
    INSTRUCTIONS
  }

  protected static final ImmutableBiMap<String, CommandSet> COMMAND_MAP =
      ImmutableBiMap.of(
          "help", CommandSet.HELP,
          "message", CommandSet.MESSAGE,
          "review", CommandSet.REVIEW,
          "review_last", CommandSet.REVIEW_LAST,
          "directives", CommandSet.DIRECTIVES,
          "forget_thread", CommandSet.FORGET_THREAD,
          "configure", CommandSet.CONFIGURE,
          "show", CommandSet.SHOW);
  private static final ImmutableBiMap<CommandSet, String> COMMAND_MAP_INVERSE =
      COMMAND_MAP.inverse();

  // Option values can be either a sequence of chars enclosed in double quotes or a sequence of
  // non-space chars.
  private static final String OPTION_VALUES = "\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"|\\S+";

  protected static final Pattern MESSAGE_COMMAND_PATTERN =
      Pattern.compile("\\s*/" + COMMAND_MAP_INVERSE.get(CommandSet.MESSAGE) + "\\b(.*)$");
  protected static final Pattern DIRECTIVE_COMMAND_PATTERN =
      Pattern.compile("\\s*/" + COMMAND_MAP_INVERSE.get(CommandSet.DIRECTIVES) + "\\b.*$");
  protected static final Pattern COMMAND_PATTERN =
      Pattern.compile("/(\\w+)\\b((?:\\s+--\\w+(?:=(?:" + OPTION_VALUES + "))?)+)?");
  protected static final Pattern OPTIONS_PATTERN =
      Pattern.compile("--(\\w+)(?:=(" + OPTION_VALUES + "))?");

  public ClientCommandBase(Configuration config) {
    super(config);
  }
}
