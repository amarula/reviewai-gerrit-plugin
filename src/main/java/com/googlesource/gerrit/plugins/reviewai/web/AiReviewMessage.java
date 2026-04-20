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
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestModifyView;
import com.google.gerrit.server.change.ChangeResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;

public class AiReviewMessage implements RestModifyView<ChangeResource, AiReviewMessage.Input> {
  private final ConfigCreator configCreator;
  private final GerritApi gerritApi;
  private final AiReviewPermission aiReviewPermission;

  @Inject
  AiReviewMessage(
      ConfigCreator configCreator, GerritApi gerritApi, AiReviewPermission aiReviewPermission) {
    this.configCreator = configCreator;
    this.gerritApi = gerritApi;
    this.aiReviewPermission = aiReviewPermission;
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
    String projectName = GerritChange.getProjectName(resource.getChange().getProject());
    ReviewInput reviewInput =
        ReviewInput.create()
            .patchSetLevelComment("@" + config.getGerritUserName() + " " + message);
    gerritApi
        .changes()
        .id(projectName, resource.getChange().getChangeId())
        .current()
        .review(reviewInput);
    return Response.ok(new Output(true));
  }

  public static class Input {
    public String message;
  }

  public static class Output {
    public final boolean ok;

    public Output(boolean ok) {
      this.ok = ok;
    }
  }
}
