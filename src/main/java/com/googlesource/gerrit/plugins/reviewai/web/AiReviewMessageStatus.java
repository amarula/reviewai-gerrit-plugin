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

import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerBaseProvider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAgentRequestStatusStore;

public class AiReviewMessageStatus
    implements RestModifyView<ChangeResource, AiReviewMessageStatus.Input> {
  private final AiReviewPermission aiReviewPermission;
  private final PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider;

  @Inject
  AiReviewMessageStatus(
      AiReviewPermission aiReviewPermission,
      PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider) {
    this.aiReviewPermission = aiReviewPermission;
    this.pluginDataHandlerBaseProvider = pluginDataHandlerBaseProvider;
  }

  @Override
  public Response<ReviewAgentRequestStatusStore.RequestStatus> apply(
      ChangeResource resource, Input input) throws Exception {
    aiReviewPermission.checkCanAiReview(resource);
    String requestId = getRequestId(input);
    if (requestId.isBlank()) {
      throw new BadRequestException("request_id is required");
    }
    ReviewAgentRequestStatusStore statusStore =
        new ReviewAgentRequestStatusStore(
            pluginDataHandlerBaseProvider.get(resource.getChange().getKey().toString()));
    return Response.ok(statusStore.get(requestId));
  }

  private String getRequestId(Input input) {
    if (input == null) {
      return "";
    }
    return input.requestId == null ? "" : input.requestId.trim();
  }

  public static class Input {
    @SerializedName(value = "request_id", alternate = {"requestId"})
    public String requestId;
  }
}
