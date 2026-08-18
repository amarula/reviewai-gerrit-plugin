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
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackPublisher;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.commands.IPatchSetProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.LangChainMemoryId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithNewLine;

@Slf4j
public class ClientCommandExecutor extends ClientCommandBase {
  private final ChangeSetData changeSetData;
  private final GerritChange change;
  private final ICodeContextPolicy codeContextPolicy;
  private final Localizer localizer;
  private final PluginDataHandlerProvider pluginDataHandlerProvider;
  private final PluginChatMemoryStore chatMemoryStore;
  private final ReviewConcernPublisher reviewConcernPublisher;
  private final ReviewFeedbackPublisher reviewFeedbackPublisher;
  private final IPatchSetProvider IPatchSetProvider;
  private final ClientCommandExtension commandExtension;

  private CommandSet command;
  private Map<BaseOptionSet, String> baseOptions;
  private String nextString;

  public ClientCommandExecutor(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      IPatchSetProvider IPatchSetProvider,
      PluginChatMemoryStore chatMemoryStore,
      ReviewConcernPublisher reviewConcernPublisher,
      ReviewFeedbackPublisher reviewFeedbackPublisher,
      ClientCommandExtension commandExtension) {
    super(config);
    this.localizer = localizer;
    this.changeSetData = changeSetData;
    this.change = change;
    this.codeContextPolicy = codeContextPolicy;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.chatMemoryStore = chatMemoryStore;
    this.reviewConcernPublisher = reviewConcernPublisher;
    this.reviewFeedbackPublisher = reviewFeedbackPublisher;
    this.IPatchSetProvider = IPatchSetProvider;
    this.commandExtension = commandExtension;
    log.debug("ClientCommandExecutor initialized.");
  }

  public void executeCommand(
      CommandSet command,
      Map<BaseOptionSet, String> baseOptions,
      Map<String, String> dynamicOptions,
      String nextString) {
    log.debug(
        "Executing Command: {}, Base Options: {}, Dynamic Options: {}",
        command,
        baseOptions,
        dynamicOptions);
    this.command = command;
    this.baseOptions = baseOptions;
    this.nextString = nextString.trim();
    switch (command) {
      case HELP -> commandHelp();
      case REVIEW -> commandForceReview();
      case SUGGEST -> commandSuggest();
      case FORGET_THREAD -> commandForgetThread();
      case CONFIGURE, DIRECTIVES, SHOW ->
          commandExtension.executeCommand(
              config,
              changeSetData,
              change,
              codeContextPolicy,
              pluginDataHandlerProvider,
              localizer,
              IPatchSetProvider,
              command,
              baseOptions,
              dynamicOptions,
              this.nextString);
    }
  }

  public void postExecuteCommand() {
    changeSetData.setHideDynamicConfigMessage(
        !changeSetData.getShowDynamicConfigMessage()
            && !DYNAMIC_CONFIG_MESSAGE_COMMANDS.contains(command));
  }

  private void commandHelp() {
    CommandSet requestedCommand = parseHelpTarget(nextString);
    if (requestedCommand == null && !nextString.isEmpty()) {
      changeSetData.setReviewSystemMessage(
          SystemMessageFormatter.getLocalizedWarningMessage(
              localizer, "message.command.help.command.unknown", nextString));
      return;
    }
    if (requestedCommand != null) {
      changeSetData.setReviewSystemMessage(getSingleCommandHelpMessage(requestedCommand));
      return;
    }
    changeSetData.setReviewSystemMessage(
        joinWithNewLine(
            List.of(
                localizer.getText("message.command.help.title"),
                "",
                localizer.getText("message.command.help.notes.detail"),
                "",
                localizer.getText("message.command.help.help"),
                localizer.getText("message.command.help.message"),
                localizer.getText("message.command.help.review"),
                localizer.getText("message.command.help.suggest"),
                localizer.getText("message.command.help.directives"),
                localizer.getText("message.command.help.forget_thread"),
                localizer.getText("message.command.help.configure"),
                localizer.getText("message.command.help.show"),
                "",
                localizer.getText("message.command.help.notes.title"),
                localizer.getText("message.command.help.notes.debug"),
                localizer.getText("message.command.help.notes.message"))));
  }

  private CommandSet parseHelpTarget(String input) {
    if (input == null || input.isBlank()) {
      return null;
    }
    String commandName = input.trim().split("\\s+")[0].replaceFirst("^/+", "");
    if (commandName.isEmpty()) {
      return null;
    }
    return COMMAND_MAP.get(commandName.toLowerCase(Locale.ROOT));
  }

  private String getSingleCommandHelpMessage(CommandSet command) {
    return switch (command) {
      case HELP ->
          joinWithNewLine(
              List.of(
                  String.format(localizer.getText("message.command.help.command.title"), "/help"),
                  "",
                  localizer.getText("message.command.help.command.help.syntax"),
                  localizer.getText("message.command.help.command.help.description")));
      case MESSAGE ->
          joinWithNewLine(
              List.of(
                  String.format(
                      localizer.getText("message.command.help.command.title"), "/message"),
                  "",
                  localizer.getText("message.command.help.command.message.syntax"),
                  localizer.getText("message.command.help.command.message.description"),
                  localizer.getText("message.command.help.command.message.note")));
      case REVIEW ->
          joinWithNewLine(
              List.of(
                  String.format(localizer.getText("message.command.help.command.title"), "/review"),
                  "",
                  localizer.getText("message.command.help.command.review.syntax"),
                  localizer.getText("message.command.help.command.review.description"),
                  localizer.getText("message.command.help.command.review.options")));
      case SUGGEST ->
          joinWithNewLine(
              List.of(
                  String.format(
                      localizer.getText("message.command.help.command.title"), "/suggest"),
                  "",
                  localizer.getText("message.command.help.command.suggest.syntax"),
                  localizer.getText("message.command.help.command.suggest.description"),
                  localizer.getText("message.command.help.command.suggest.options")));
      case DIRECTIVES ->
          joinWithNewLine(
              List.of(
                  String.format(
                      localizer.getText("message.command.help.command.title"), "/directives"),
                  "",
                  localizer.getText("message.command.help.command.directives.syntax"),
                  localizer.getText("message.command.help.command.directives.description"),
                  localizer.getText("message.command.help.command.directives.options"),
                  localizer.getText("message.command.help.command.directives.note")));
      case FORGET_THREAD ->
          joinWithNewLine(
              List.of(
                  String.format(
                      localizer.getText("message.command.help.command.title"), "/forget_thread"),
                  "",
                  localizer.getText("message.command.help.command.forget_thread.syntax"),
                  localizer.getText("message.command.help.command.forget_thread.description")));
      case CONFIGURE ->
          joinWithNewLine(
              List.of(
                  String.format(
                      localizer.getText("message.command.help.command.title"), "/configure"),
                  "",
                  localizer.getText("message.command.help.command.configure.syntax"),
                  localizer.getText("message.command.help.command.configure.description"),
                  localizer.getText("message.command.help.command.configure.options"),
                  localizer.getText("message.command.help.command.configure.note")));
      case SHOW ->
          joinWithNewLine(
              List.of(
                  String.format(localizer.getText("message.command.help.command.title"), "/show"),
                  "",
                  localizer.getText("message.command.help.command.show.syntax"),
                  localizer.getText("message.command.help.command.show.description"),
                  localizer.getText("message.command.help.command.show.options"),
                  localizer.getText("message.command.help.command.show.note")));
    };
  }

  private void commandForceReview() {
    changeSetData.setForcedReview(true);
    changeSetData.setHideAiReview(false);
    changeSetData.setReviewSystemMessage(null);
    log.info("Forced review command applied to the entire Change Set");
    if (baseOptions.containsKey(BaseOptionSet.TOPIC)) {
      changeSetData.setForcedTopicReview(true);
      log.info("Forced review command applied to the current topic");
    }
    applyReviewScopeOption();
    if (baseOptions.containsKey(BaseOptionSet.FILTER)) {
      boolean value = Boolean.parseBoolean(baseOptions.get(BaseOptionSet.FILTER));
      log.debug("Option 'replyFilterEnabled' set to {}", value);
      changeSetData.setReplyFilterEnabled(value);
    } else if (baseOptions.containsKey(BaseOptionSet.DEBUG)) {
      commandExtension.applyReviewDebugOption(changeSetData, baseOptions);
    }
  }

  private void commandSuggest() {
    changeSetData.setForcedReview(true);
    changeSetData.setHideAiReview(false);
    changeSetData.setReviewSystemMessage(null);
    changeSetData.setSuggestMode(true);
    changeSetData.setReplyFilterEnabled(false);
    log.info("Suggest command applied to the Change Set");
    applyReviewScopeOption();
  }

  private void applyReviewScopeOption() {
    if (!baseOptions.containsKey(BaseOptionSet.SCOPE)) {
      return;
    }
    ReviewScope scope =
        ReviewScope.fromCommandOption(baseOptions.get(BaseOptionSet.SCOPE));
    changeSetData.setReviewScope(scope);
    switch (scope) {
      case FULL -> log.info("Forced review command scoped to the full Change Set");
      case PATCHSET -> {
        changeSetData.setForcedStagedReview(true);
        changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_CODE);
        log.info("Forced review command scoped to the PatchSet");
      }
      case COMMIT_MESSAGE -> {
        changeSetData.setForcedStagedReview(true);
        changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
        log.info("Forced review command scoped to the commit message");
      }
    }
  }

  private void commandForgetThread() {
    PluginDataHandler changeDataHandler = pluginDataHandlerProvider.getChangeScope();
    log.info(
        "Removing conversation ID '{}' for Change Set",
        changeDataHandler.getValue(OpenAiConversation.KEY_CONVERSATION_ID));
    new OpenAiConversation(config, pluginDataHandlerProvider).clear();
    clearLangChainMemory();
    clearReviewConcerns();
    changeSetData.setReviewSystemMessage(localizer.getText("message.command.thread.forget"));
  }

  private void clearReviewConcerns() {
    if (reviewConcernPublisher != null) {
      reviewConcernPublisher.clear(change);
    }
    if (reviewFeedbackPublisher != null) {
      reviewFeedbackPublisher.forget(change);
    }
    changeSetData.setPreviousReviewConcernLedger(null);
    changeSetData.setIncrementalPatchSet(null);
    changeSetData.setReviewFeedbackMemory(null);
    changeSetData.setPendingReviewFeedbackCommentIds(List.of());
    changeSetData.setReviewFeedbackClassified(false);
  }

  private void clearLangChainMemory() {
    if (chatMemoryStore == null) {
      return;
    }
    chatMemoryStore.deleteMessagesForChangeSet(
        change.getFullChangeId(), LangChainMemoryId.getPatchSetNumber(change));
  }
}
