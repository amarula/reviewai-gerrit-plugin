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

import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.change.ChangeResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings.AiBackends;

public class ReviewAgentModel implements RestReadView<ChangeResource> {
  private final ConfigCreator configCreator;
  private final AiReviewPermission aiReviewPermission;

  @Inject
  ReviewAgentModel(ConfigCreator configCreator, AiReviewPermission aiReviewPermission) {
    this.configCreator = configCreator;
    this.aiReviewPermission = aiReviewPermission;
  }

  @Override
  public Response<Output> apply(ChangeResource resource) throws Exception {
    Configuration config =
        configCreator.createConfig(resource.getProject(), resource.getChange().getKey());
    return Response.ok(
        new Output(
            config.getAiBackend().name(),
            config.getAiBackend() == AiBackends.OPENAI
                ? AiBackends.OPENAI.name()
                : config.getLcProvider().name(),
            config.getAiModel(),
            aiReviewPermission.canAiReview(resource)));
  }

  public static class Output {
    public final String aiBackend;
    public final String provider;
    public final String aiModel;
    public final Boolean canAiReview;

    public Output(String aiBackend, String provider, String aiModel, Boolean canAiReview) {
      this.aiBackend = aiBackend;
      this.provider = provider;
      this.aiModel = aiModel;
      this.canAiReview = canAiReview;
    }
  }
}
