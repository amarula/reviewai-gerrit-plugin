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

package com.googlesource.gerrit.plugins.reviewai.web;

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerBaseProvider;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

public class DevAiReviewConfig implements RestReadView<ChangeResource> {
  private final ConfigCreator configCreator;
  private final AiReviewPermission aiReviewPermission;
  private final PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider;

  @Inject
  DevAiReviewConfig(
      ConfigCreator configCreator,
      AiReviewPermission aiReviewPermission,
      PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider) {
    this.configCreator = configCreator;
    this.aiReviewPermission = aiReviewPermission;
    this.pluginDataHandlerBaseProvider = pluginDataHandlerBaseProvider;
  }

  @Override
  public Response<Output> apply(ChangeResource resource) throws Exception {
    aiReviewPermission.checkCanAiReview(resource);
    Configuration config =
        configCreator.createConfig(resource.getProject(), resource.getChange().getKey());

    // Read dynamic config directly from per-change plugin data, avoiding
    // DynamicConfigManager which requires event-scoped GerritChange.
    String changeKey = resource.getChange().getKey().toString();
    PluginDataHandler handler = pluginDataHandlerBaseProvider.get(changeKey);
    Map<String, String> rawDynamicConfig = handler.getJsonObjectValue("dynamicConfig", String.class);

    // Replicate getDynamicConfigForDisplay filtering
    Map<String, String> dynamicConfig = new LinkedHashMap<>();
    if (rawDynamicConfig != null && !rawDynamicConfig.isEmpty()) {
      if (rawDynamicConfig.size() == 1
          && rawDynamicConfig.containsKey("selectedAiModel")
          && rawDynamicConfig.get("selectedAiModel") != null
          && rawDynamicConfig.get("selectedAiModel").equals(
              config.getDefaultRealAiModelRoute()
                  .map(aiModelRoute -> aiModelRoute.modelRoute())
                  .orElse(""))) {
        // Only key is selectedAiModel and it matches the default — hide it
      } else {
        dynamicConfig.putAll(rawDynamicConfig);
      }
    }

    try (ManualRequestContext ignored = config.openRequestContext()) {
      // Static configuration
      TreeMap<String, String> dumpMap = config.dumpConfigMap();
      Map<String, String> staticConfig = new LinkedHashMap<>();
      if (dumpMap != null) {
        staticConfig.putAll(dumpMap);
      }

      // Models
      List<String> models = config.getAiModels();

      // Condition and trigger info
      String projectName = GerritChange.getProjectName(resource.getChange().getProject());

      Output output = new Output();
      output.staticConfig = staticConfig;
      output.dynamicConfig = dynamicConfig;
      output.aiModels = models;
      output.selectedModel = config.getSelectedAiModelRoute().modelRoute();
      output.aiReviewApplicableIf = config.getAiReviewApplicableIf();
      output.votingEnabled = config.isVotingEnabled();
      output.aiProviderType = config.getAiProviderType() != null
          ? config.getAiProviderType().name() : null;
      output.codeContextPolicy = config.getCodeContextPolicy() != null
          ? config.getCodeContextPolicy().name() : null;
      output.aiReviewCommitMessages = config.getAiReviewCommitMessages();
      output.ignoreResolvedAiComments = config.getIgnoreResolvedAiComments();
      output.ignoreOutdatedInlineComments = config.getIgnoreOutdatedInlineComments();
      output.maxReviewLines = config.getMaxReviewLines();
      output.projectName = projectName;

      return Response.ok(output);
    }
  }

  public static class Output {
    @SerializedName("static_config")
    public Map<String, String> staticConfig = Map.of();

    @SerializedName("dynamic_config")
    public Map<String, String> dynamicConfig = Map.of();

    @SerializedName("ai_models")
    public List<String> aiModels = List.of();

    @SerializedName("selected_model")
    public String selectedModel;

    @SerializedName("ai_review_applicable_if")
    public String aiReviewApplicableIf;

    @SerializedName("voting_enabled")
    public boolean votingEnabled;

    @SerializedName("ai_provider_type")
    public String aiProviderType;

    @SerializedName("code_context_policy")
    public String codeContextPolicy;

    @SerializedName("ai_review_commit_messages")
    public boolean aiReviewCommitMessages;

    @SerializedName("ignore_resolved_ai_comments")
    public boolean ignoreResolvedAiComments;

    @SerializedName("ignore_outdated_inline_comments")
    public boolean ignoreOutdatedInlineComments;

    @SerializedName("max_review_lines")
    public int maxReviewLines;

    @SerializedName("project_name")
    public String projectName;
  }
}
