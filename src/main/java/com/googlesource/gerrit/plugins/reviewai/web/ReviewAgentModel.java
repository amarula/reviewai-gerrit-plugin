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
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiAdministratorGroup;
import java.util.List;

public class ReviewAgentModel implements RestReadView<ChangeResource> {
  private final ConfigCreator configCreator;
  private final AiReviewPermission aiReviewPermission;
  private final GroupCache groupCache;
  private final PermissionBackend permissionBackend;

  @Inject
  ReviewAgentModel(
      ConfigCreator configCreator,
      AiReviewPermission aiReviewPermission,
      GroupCache groupCache,
      PermissionBackend permissionBackend) {
    this.configCreator = configCreator;
    this.aiReviewPermission = aiReviewPermission;
    this.groupCache = groupCache;
    this.permissionBackend = permissionBackend;
  }

  @Override
  public Response<Output> apply(ChangeResource resource) throws Exception {
    Configuration config =
        configCreator.createConfig(resource.getProject(), resource.getChange().getKey());
    boolean administratorUser =
        AiAdministratorGroup.isAdministrator(
            config, groupCache, permissionBackend, resource.getUser());
    List<String> models = config.getAiModels(administratorUser);
    return Response.ok(
        new Output(
            models.stream().map(Model::fromRoute).toList(),
            getDefaultModelId(config, models),
            aiReviewPermission.canAiReview(resource)));
  }

  private String getDefaultModelId(Configuration config, List<String> models) {
    String selectedModelId = config.getSelectedAiModelRoute().modelRoute();
    if (models.contains(selectedModelId)) {
      return selectedModelId;
    }
    // The selected model may be a mock route hidden from non-admin sidebar responses.
    // Keep the advertised default consistent with the visible model list.
    return config
        .getDefaultRealAiModelRoute()
        .map(AiModelRoute::modelRoute)
        .filter(models::contains)
        .orElseGet(() -> models.isEmpty() ? null : models.getFirst());
  }

  public static class Output {
    public final List<Model> models;
    public final String defaultModelId;
    public final Boolean canAiReview;

    public Output(List<Model> models, String defaultModelId, Boolean canAiReview) {
      this.models = models;
      this.defaultModelId = defaultModelId;
      this.canAiReview = canAiReview;
    }
  }

  public static class Model {
    public final String modelId;
    public final String provider;
    public final String model;

    public Model(String modelId, String provider, String model) {
      this.modelId = modelId;
      this.provider = provider;
      this.model = model;
    }

    private static Model fromRoute(String route) {
      return AiModelRoute.parse(route)
          .map(modelRoute -> new Model(route, modelRoute.providerRoute(), modelRoute.model()))
          .orElseGet(() -> new Model(route, route, ""));
    }
  }
}
