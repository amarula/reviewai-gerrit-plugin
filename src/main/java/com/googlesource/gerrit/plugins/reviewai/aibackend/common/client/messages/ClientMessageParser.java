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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandParser;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.commands.IPatchSetProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Matcher;

@Slf4j
public class ClientMessageParser extends ClientMessageBase {
  private final ClientCommandParser clientCommandParser;

  public ClientMessageParser(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      IPatchSetProvider IPatchSetProvider,
      PluginChatMemoryStore chatMemoryStore) {
    super(config);
    clientCommandParser =
        new ClientCommandParser(
            config,
            changeSetData,
            change,
            codeContextPolicy,
            pluginDataHandlerProvider,
            localizer,
            IPatchSetProvider,
            chatMemoryStore);
    log.debug("ClientMessageParser initialized with bot mention pattern: {}", botMentionPattern);
  }

  public boolean isBotAddressed(String message) {
    log.debug("Checking if message addresses the bot: {}", message);
    Matcher userMatcher = botMentionPattern.matcher(message);
    if (!userMatcher.find()) {
      log.debug(
          "Skipping action since the comment does not mention the AI bot."
              + " Expected bot name in comment: {}, Actual comment text: {}",
          config.getGerritUserName(),
          message);
      return false;
    }
    return true;
  }

  public boolean parseCommands(String comment) {
    log.debug("Parsing commands from comment: {}", comment);
    return clientCommandParser.parseCommands(comment);
  }
}
