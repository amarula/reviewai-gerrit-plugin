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
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyNone;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyOnDemand;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksDynamicConfiguration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.gerrit.GerritClientPatchSetOpenAi;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.code.context.OpenAiCodeContextPolicyOnDemand;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManager;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerBaseProvider;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManager.KEY_DYNAMIC_CONFIG;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

public class AiReviewMessage implements RestModifyView<ChangeResource, AiReviewMessage.Input> {
  private final ConfigCreator configCreator;
  private final GerritApi gerritApi;
  private final AiReviewPermission aiReviewPermission;
  private final PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider;
  private final AccountCache accountCache;
  private final GitRepositoryManager repositoryManager;
  private final Path pluginDataPath;

  @Inject
  AiReviewMessage(
      ConfigCreator configCreator,
      GerritApi gerritApi,
      AiReviewPermission aiReviewPermission,
      PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider,
      AccountCache accountCache,
      GitRepositoryManager repositoryManager,
      @PluginData Path pluginDataPath) {
    this.configCreator = configCreator;
    this.gerritApi = gerritApi;
    this.aiReviewPermission = aiReviewPermission;
    this.pluginDataHandlerBaseProvider = pluginDataHandlerBaseProvider;
    this.accountCache = accountCache;
    this.repositoryManager = repositoryManager;
    this.pluginDataPath = pluginDataPath;
  }

  @Override
  public Response<Output> apply(ChangeResource resource, Input input) throws Exception {
    String message = input == null || input.message == null ? "" : input.message.trim();
    if (message.isEmpty()) {
      throw new BadRequestException("message is required");
    }

    Configuration config =
        configCreator.createConfig(resource.getProject(), resource.getChange().getKey());
    aiReviewPermission.checkCanAiReview(resource);
    storeSelectedModel(resource, input, config);
    Optional<Output> directResponse = getReviewAgentDirectResponse(resource, config, input, message);
    if (directResponse.isPresent()) {
      return Response.ok(directResponse.get());
    }
    String reviewAgentPreamble = getReviewAgentPreamble(resource, config, input, message);
    String projectName = GerritChange.getProjectName(resource.getChange().getProject());
    ReviewInput reviewInput =
        ReviewInput.create()
            .patchSetLevelComment("@" + config.getGerritUserName() + " " + message);
    gerritApi
        .changes()
        .id(projectName, resource.getChange().getChangeId())
        .current()
        .review(reviewInput);
    return Response.ok(new Output(true, reviewAgentPreamble));
  }

  public static class Input {
    public String message;

    @SerializedName(value = "model_id", alternate = {"modelId"})
    public String modelId;

    @SerializedName(value = "model_name", alternate = {"modelName"})
    public String modelName;

    @SerializedName(value = "review_agent", alternate = {"reviewAgent"})
    public Boolean reviewAgent;
  }

  public static class Output {
    public final boolean ok;

    @SerializedName(value = "response_text", alternate = {"responseText"})
    public final String responseText;

    public Output(boolean ok) {
      this(ok, null);
    }

    public Output(boolean ok, String responseText) {
      this.ok = ok;
      this.responseText = responseText;
    }
  }

  private Optional<Output> getReviewAgentDirectResponse(
      ChangeResource resource, Configuration config, Input input, String message) {
    if (input == null
        || !Boolean.TRUE.equals(input.reviewAgent)
        || !ClientCommandBase.shouldSkipGerritMessage(message)) {
      return Optional.empty();
    }

    ReviewAgentCommandContext commandContext =
        parseReviewAgentCommand(resource, config, message, true);
    return Optional.of(
        new Output(
            true,
            getDirectResponseText(
                commandContext.changeSetData(),
                commandContext.pluginDataHandlerProvider(),
                commandContext.localizer())));
  }

  private String getDirectResponseText(
      ChangeSetData changeSetData,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer) {
    List<String> messages = new ArrayList<>();
    if (!changeSetData.getHideDynamicConfigMessage()) {
      Optional.ofNullable(getDynamicConfigurationMessage(pluginDataHandlerProvider, localizer))
          .ifPresent(messages::add);
    }
    if (changeSetData.getReviewSystemMessage() != null) {
      messages.add(changeSetData.getReviewSystemMessage());
    }
    return joinWithDoubleNewLine(messages);
  }

  private String getReviewAgentPreamble(
      ChangeResource resource, Configuration config, Input input, String message) {
    if (input == null || !Boolean.TRUE.equals(input.reviewAgent)) {
      return null;
    }
    ReviewAgentCommandContext commandContext =
        parseReviewAgentCommand(resource, config, message, false);
    if (!commandContext.changeSetData().getShowDynamicConfigMessage()
        || commandContext
            .changeSetData()
            .hasParsedCommand(ClientCommandBase.commandName(CommandSet.CONFIGURE))) {
      return null;
    }
    return getDynamicConfigurationMessage(
        commandContext.pluginDataHandlerProvider(), commandContext.localizer());
  }

  private ReviewAgentCommandContext parseReviewAgentCommand(
      ChangeResource resource, Configuration config, String message, boolean executeCommands) {
    GerritChange change =
        new GerritChange(
            resource.getProject(), resource.getChange().getDest(), resource.getChange().getKey());
    ChangeSetData changeSetData =
        new ChangeSetData(
            getAiAccountId(config), config.getVotingMinScore(), config.getVotingMaxScore());
    Localizer localizer = new Localizer(config);
    PluginDataHandlerProvider pluginDataHandlerProvider =
        new PluginDataHandlerProvider(pluginDataPath, change);
    GerritClientPatchSetOpenAi gerritClientPatchSet =
        new GerritClientPatchSetOpenAi(config, accountCache, repositoryManager);
    new ClientCommandParser(
            config,
            changeSetData,
            change,
            getCodeContextPolicy(config, change),
            new GitRepoFiles(),
            pluginDataHandlerProvider,
            localizer,
            () -> gerritClientPatchSet.getPatchSet(changeSetData, change))
        .parseCommands(message, executeCommands);
    return new ReviewAgentCommandContext(changeSetData, pluginDataHandlerProvider, localizer);
  }

  private String getDynamicConfigurationMessage(
      PluginDataHandlerProvider pluginDataHandlerProvider, Localizer localizer) {
    Map<String, String> dynamicConfig =
        new DynamicConfigManager(pluginDataHandlerProvider).getDynamicConfig();
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

  private ICodeContextPolicy getCodeContextPolicy(Configuration config, GerritChange change) {
    if (config.getCodeContextPolicy() != CodeContextPolicies.ON_DEMAND) {
      return new CodeContextPolicyNone(config);
    }
    GitRepoFiles gitRepoFiles = new GitRepoFiles();
    if (config.getSelectedAiModelRoute() != null
        && !config.getSelectedAiModelRoute().isLangChain()) {
      return new OpenAiCodeContextPolicyOnDemand(config, change, gitRepoFiles);
    }
    return new CodeContextPolicyOnDemand(config);
  }

  private void storeSelectedModel(ChangeResource resource, Input input, Configuration config)
      throws BadRequestException {
    String modelId = getSelectedModelId(input);
    if (modelId.isBlank()) {
      return;
    }
    if (!config.getAiModels().contains(modelId)) {
      throw new BadRequestException("invalid model: " + modelId);
    }
    PluginDataHandler changeDataHandler =
        pluginDataHandlerBaseProvider.get(resource.getChange().getKey().toString());
    Map<String, String> dynamicConfig =
        changeDataHandler.getJsonObjectValue(KEY_DYNAMIC_CONFIG, String.class);
    if (dynamicConfig == null) {
      dynamicConfig = new HashMap<>();
    }
    dynamicConfig.put("selectedAiModel", modelId);
    changeDataHandler.setJsonValue(KEY_DYNAMIC_CONFIG, dynamicConfig);
  }

  private String getSelectedModelId(Input input) {
    if (input == null) {
      return "";
    }
    if (input.modelName != null && !input.modelName.isBlank()) {
      return input.modelName.trim();
    }
    return input.modelId == null ? "" : input.modelId.trim();
  }
}
