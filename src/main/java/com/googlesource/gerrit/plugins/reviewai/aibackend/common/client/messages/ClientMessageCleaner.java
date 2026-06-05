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
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandCleaner;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksCleaner;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_DEFAULT_MESSAGE_COMMENTS;
import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_DEFAULT_MESSAGE_PATCH_SET;

@Slf4j
public class ClientMessageCleaner extends ClientMessageBase {
  private final Pattern messageHeadingPattern;
  private final DebugCodeBlocksCleaner debugCodeBlocksCleaner;
  private final ClientCommandCleaner clientCommandCleaner;

  @Getter protected String message;

  public ClientMessageCleaner(Configuration config, String message, Localizer localizer) {
    super(config);
    this.message = message;
    debugCodeBlocksCleaner = new DebugCodeBlocksCleaner(localizer);
    clientCommandCleaner = new ClientCommandCleaner(config);
    messageHeadingPattern =
        Pattern.compile(
            SystemMessageFormatter.getMessagePrefixPattern(localizer)
                + ".*$|^"
                + GERRIT_DEFAULT_MESSAGE_PATCH_SET
                + " \\d+:[^\\n]*(?:\\s+\\(\\d+ "
                + GERRIT_DEFAULT_MESSAGE_COMMENTS
                + "?\\)\\s*)?",
            Pattern.DOTALL);
    log.debug("ClientMessageCleaner initialized with bot mention pattern: {}", botMentionPattern);
  }

  public ClientMessageCleaner removeHeadings() {
    log.debug("Removing headings from message.");
    message = messageHeadingPattern.matcher(message).replaceAll("");
    log.debug("Message after removing headings: {}", message);
    return this;
  }

  public ClientMessageCleaner removeMentions() {
    log.debug("Removing bot mentions from message.");
    message = botMentionPattern.matcher(message).replaceAll("").trim();
    log.debug("Message after removing mentions: {}", message);
    return this;
  }

  public ClientMessageCleaner removeCommands() {
    log.debug("Removing commands from message.");
    message = clientCommandCleaner.removeCommands(message);
    log.debug("Message after removing commands: {}", message);
    return this;
  }

  public ClientMessageCleaner removeDebugCodeBlocks() {
    log.debug("Removing debug code blocks for review.");
    message = debugCodeBlocksCleaner.removeDebugCodeBlocks(message);
    log.debug("Message after removing debug code blocks: {}", message);
    return this;
  }
}
