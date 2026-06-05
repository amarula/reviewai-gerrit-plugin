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

package com.googlesource.gerrit.plugins.reviewai.web;

import com.google.gerrit.entities.Account;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyNone;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyOnDemand;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.BaseOptionSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksDynamicConfiguration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientPatchSetReviewAi;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManager;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

class ReviewAgentResponseService {
  private final GitRepositoryManager repositoryManager;
  private final Path pluginDataPath;
  private final PluginChatMemoryStore chatMemoryStore;
  private final ReviewAiDb db;

  ReviewAgentResponseService(
      GitRepositoryManager repositoryManager,
      Path pluginDataPath,
      PluginChatMemoryStore chatMemoryStore,
      ReviewAiDb db) {
    this.repositoryManager = repositoryManager;
    this.pluginDataPath = pluginDataPath;
    this.chatMemoryStore = chatMemoryStore;
    this.db = db;
  }

  Optional<AiReviewMessage.Output> getDirectResponse(
      ChangeResource resource,
      Configuration config,
      AiReviewMessage.Input input,
      String message) {
    if (input == null
        || !Boolean.TRUE.equals(input.reviewAgent)
        || !ClientCommandBase.shouldSkipGerritMessage(message)) {
      return Optional.empty();
    }

    ReviewAgentCommandContext commandContext =
        parseReviewAgentCommand(resource, config, message, true);
    return Optional.of(
        new AiReviewMessage.Output(
            true,
            getDirectResponseText(
                config,
                commandContext.changeSetData(),
                commandContext.pluginDataHandlerProvider(),
                commandContext.localizer(),
                false),
            false));
  }

  Optional<AiReviewMessage.Output> getPreflightSystemResponse(
      ChangeResource resource,
      Configuration config,
      AiReviewMessage.Input input,
      String message) {
    if (input == null || !Boolean.TRUE.equals(input.reviewAgent)) {
      return Optional.empty();
    }
    ReviewAgentCommandContext commandContext =
        parseReviewAgentCommand(resource, config, message, false);
    if (commandContext.changeSetData().getReviewSystemMessage() == null) {
      return Optional.empty();
    }
    return Optional.of(
        new AiReviewMessage.Output(
            true,
            getDirectResponseText(
                config,
                commandContext.changeSetData(),
                commandContext.pluginDataHandlerProvider(),
                commandContext.localizer(),
                true),
            false));
  }

  String getPreamble(
      ChangeResource resource,
      Configuration config,
      AiReviewMessage.Input input,
      String message) {
    if (input == null || !Boolean.TRUE.equals(input.reviewAgent)) {
      return null;
    }
    ReviewAgentCommandContext commandContext =
        parseReviewAgentCommand(resource, config, message, false);
    List<String> messages = new ArrayList<>();
    Optional.ofNullable(getPartialReviewPositiveScoreMessage(commandContext))
        .ifPresent(messages::add);
    if (!commandContext.changeSetData().getShowDynamicConfigMessage()
        || commandContext
            .changeSetData()
            .hasParsedCommand(ClientCommandBase.commandName(CommandSet.CONFIGURE))) {
      return messages.isEmpty() ? null : joinWithDoubleNewLine(messages);
    }
    Optional.ofNullable(
            getDynamicConfigurationMessage(
                config, commandContext.pluginDataHandlerProvider(), commandContext.localizer()))
        .ifPresent(messages::add);
    return messages.isEmpty() ? null : joinWithDoubleNewLine(messages);
  }

  private String getDirectResponseText(
      Configuration config,
      ChangeSetData changeSetData,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      boolean prefixSystemMessage) {
    List<String> messages = new ArrayList<>();
    if (!changeSetData.getHideDynamicConfigMessage()) {
      Optional.ofNullable(
              getDynamicConfigurationMessage(config, pluginDataHandlerProvider, localizer))
          .ifPresent(messages::add);
    }
    if (changeSetData.getReviewSystemMessage() != null) {
      messages.add(
          prefixSystemMessage
              ? SystemMessageFormatter.getPrefixedSystemMessage(
                  localizer, changeSetData.getReviewSystemMessage())
              : changeSetData.getReviewSystemMessage());
    }
    return joinWithDoubleNewLine(messages);
  }

  private String getPartialReviewPositiveScoreMessage(ReviewAgentCommandContext commandContext) {
    ChangeSetData changeSetData = commandContext.changeSetData();
    boolean partialReview =
        changeSetData.hasParsedCommandOption(
                ClientCommandBase.commandName(CommandSet.REVIEW),
                BaseOptionSet.SCOPE.name(),
                ReviewScope.PATCHSET.getCommandOptionValue())
            || changeSetData.hasParsedCommandOption(
                ClientCommandBase.commandName(CommandSet.REVIEW),
                BaseOptionSet.SCOPE.name(),
                ReviewScope.COMMIT_MESSAGE.getCommandOptionValue());
    if (!partialReview) {
      return null;
    }
    Localizer localizer = commandContext.localizer();
    return SystemMessageFormatter.getPrefixedSystemMessage(
        localizer, localizer.getText("message.review.partial.positive.score.skipped"));
  }

  private ReviewAgentCommandContext parseReviewAgentCommand(
      ChangeResource resource, Configuration config, String message, boolean executeCommands) {
    GerritChange change =
        new GerritChange(
            resource.getProject(), resource.getChange().getDest(), resource.getChange().getKey());
    if (resource.getChange().currentPatchSetId() != null) {
      change.setPatchSetNumber(resource.getChange().currentPatchSetId().get());
    }
    ChangeSetData changeSetData =
        new ChangeSetData(
            getAiAccountId(config), config.getVotingMinScore(), config.getVotingMaxScore());
    Localizer localizer = new Localizer(config);
    PluginDataHandlerProvider pluginDataHandlerProvider =
        new PluginDataHandlerProvider(pluginDataPath, change, db);
    GerritClientPatchSetReviewAi gerritClientPatchSet =
        new GerritClientPatchSetReviewAi(config, repositoryManager);
    new ClientCommandParser(
            config,
            changeSetData,
            change,
            getCodeContextPolicy(config),
            pluginDataHandlerProvider,
            localizer,
            () -> gerritClientPatchSet.getPatchSet(changeSetData, change),
            chatMemoryStore)
        .parseCommands(message, executeCommands);
    return new ReviewAgentCommandContext(changeSetData, pluginDataHandlerProvider, localizer);
  }

  private String getDynamicConfigurationMessage(
      Configuration config, PluginDataHandlerProvider pluginDataHandlerProvider, Localizer localizer) {
    Map<String, String> dynamicConfig =
        new DynamicConfigManager(pluginDataHandlerProvider).getDynamicConfigForDisplay(config);
    if (dynamicConfig == null || dynamicConfig.isEmpty()) {
      return null;
    }
    return new DebugCodeBlocksDynamicConfiguration(localizer).getDebugCodeBlock(dynamicConfig);
  }

  private record ReviewAgentCommandContext(
      ChangeSetData changeSetData,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer) {}

  private int getAiAccountId(Configuration config) {
    return Optional.ofNullable(config.getUserId()).map(Account.Id::get).orElse(0);
  }

  private ICodeContextPolicy getCodeContextPolicy(Configuration config) {
    if (config.getCodeContextPolicy() != CodeContextPolicies.ON_DEMAND) {
      return new CodeContextPolicyNone(config);
    }
    return new CodeContextPolicyOnDemand(config);
  }
}
