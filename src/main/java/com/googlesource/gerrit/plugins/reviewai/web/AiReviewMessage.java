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

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.annotations.PluginData;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerBaseProvider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAgentRequestStatusStore;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManager.KEY_DYNAMIC_CONFIG;

public class AiReviewMessage implements RestModifyView<ChangeResource, AiReviewMessage.Input> {
  private final ConfigCreator configCreator;
  private final GerritApi gerritApi;
  private final AiReviewPermission aiReviewPermission;
  private final PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider;
  private final ReviewAgentResponseService reviewAgentResponseService;
  private final ReviewAgentGerritMessageIdFinder gerritMessageIdFinder;

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
    reviewAgentResponseService =
        new ReviewAgentResponseService(accountCache, repositoryManager, pluginDataPath);
    gerritMessageIdFinder = new ReviewAgentGerritMessageIdFinder();
  }

  @Override
  public Response<Output> apply(ChangeResource resource, Input input) throws Exception {
    String message = input == null || input.message == null ? "" : input.message.trim();
    if (message.isEmpty()) {
      throw new BadRequestException("message is required");
    }

    Configuration config =
        configCreator.createConfig(resource.getProject(), resource.getChange().getKey());
    String requestId = getRequestId(input);
    boolean reviewAgent = input != null && Boolean.TRUE.equals(input.reviewAgent);
    if (reviewAgent && requestId.isBlank()) {
      requestId = UUID.randomUUID().toString();
    }
    ReviewAgentRequestStatusStore statusStore = getStatusStore(resource);
    aiReviewPermission.checkCanAiReview(resource);
    storeSelectedModel(resource, input, config);
    Optional<Output> directResponse =
        reviewAgentResponseService.getDirectResponse(resource, config, input, message);
    if (directResponse.isPresent()) {
      Output output = directResponse.get().withRequestId(requestId);
      completeStatus(statusStore, requestId, output.responseText);
      return Response.ok(output);
    }
    Optional<Output> preflightSystemResponse =
        reviewAgentResponseService.getPreflightSystemResponse(resource, config, input, message);
    if (preflightSystemResponse.isPresent()) {
      Output output = preflightSystemResponse.get().withRequestId(requestId);
      completeStatus(statusStore, requestId, output.responseText);
      return Response.ok(output);
    }
    String reviewAgentPreamble =
        reviewAgentResponseService.getPreamble(resource, config, input, message);
    String projectName = GerritChange.getProjectName(resource.getChange().getProject());
    if (reviewAgent) {
      statusStore.pending(requestId, message);
    }
    String postedMessage = "@" + config.getGerritUserName() + " " + message;
    ReviewInput reviewInput =
        ReviewInput.create().patchSetLevelComment(postedMessage);
    String outputRequestId = requestId;
    ChangeApi changeApi =
        gerritApi.changes().id(projectName, resource.getChange().getChangeId());
    ReviewResult reviewResult;
    try {
      reviewResult = changeApi.current().review(reviewInput);
    } catch (Exception e) {
      failStatus(statusStore, requestId, e.getMessage());
      throw e;
    }
    if (reviewAgent) {
      try {
        Optional<String> gerritMessageId =
            gerritMessageIdFinder.findPostedChangeMessageId(changeApi, reviewResult, postedMessage);
        if (gerritMessageId.isPresent()) {
          outputRequestId = gerritMessageId.get();
          statusStore.move(requestId, outputRequestId);
        }
      } catch (Exception ignored) {
        // Keep the provisional id if Gerrit accepted the review but id lookup is unavailable.
      }
    }
    return Response.ok(new Output(true, reviewAgentPreamble).withRequestId(outputRequestId));
  }

  public static class Input {
    public String message;

    @SerializedName(value = "model_id", alternate = {"modelId"})
    public String modelId;

    @SerializedName(value = "model_name", alternate = {"modelName"})
    public String modelName;

    @SerializedName(value = "review_agent", alternate = {"reviewAgent"})
    public Boolean reviewAgent;

    @SerializedName(value = "request_id", alternate = {"requestId"})
    public String requestId;
  }

  public static class Output {
    public final boolean ok;

    @SerializedName(value = "response_text", alternate = {"responseText"})
    public final String responseText;

    @SerializedName(value = "wait_for_assistant_reply", alternate = {"waitForAssistantReply"})
    public final boolean waitForAssistantReply;

    @SerializedName(value = "request_id", alternate = {"requestId"})
    public final String requestId;

    public Output(boolean ok) {
      this(ok, null, true);
    }

    public Output(boolean ok, String responseText) {
      this(ok, responseText, true);
    }

    public Output(boolean ok, String responseText, boolean waitForAssistantReply) {
      this(ok, responseText, waitForAssistantReply, null);
    }

    public Output(boolean ok, String responseText, boolean waitForAssistantReply, String requestId) {
      this.ok = ok;
      this.responseText = responseText;
      this.waitForAssistantReply = waitForAssistantReply;
      this.requestId = requestId;
    }

    public Output withRequestId(String requestId) {
      if (requestId == null || requestId.isBlank()) {
        return this;
      }
      return new Output(ok, responseText, waitForAssistantReply, requestId);
    }
  }

  private ReviewAgentRequestStatusStore getStatusStore(ChangeResource resource) {
    return new ReviewAgentRequestStatusStore(
        pluginDataHandlerBaseProvider.get(resource.getChange().getKey().toString()));
  }

  private String getRequestId(Input input) {
    return input == null || input.requestId == null ? "" : input.requestId.trim();
  }

  private void completeStatus(
      ReviewAgentRequestStatusStore statusStore, String requestId, String responseText) {
    if (requestId != null && !requestId.isBlank()) {
      statusStore.completed(requestId, responseText);
    }
  }

  private void failStatus(
      ReviewAgentRequestStatusStore statusStore, String requestId, String responseText) {
    if (requestId != null && !requestId.isBlank()) {
      statusStore.failed(requestId, responseText);
    }
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
