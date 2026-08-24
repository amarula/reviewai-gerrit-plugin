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

package com.googlesource.gerrit.plugins.reviewai;

import com.google.gerrit.extensions.registration.DynamicItem;
import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.lifecycle.LifecycleModule;
import com.google.gerrit.server.avatar.AvatarProvider;
import com.google.gerrit.server.change.ChangeResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.avatar.ReviewAiAvatarPluginDetector;
import com.googlesource.gerrit.plugins.reviewai.avatar.ReviewAiAvatarProvider;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandExtension;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.DisabledClientCommandExtension;
import com.googlesource.gerrit.plugins.reviewai.listener.LoggingConfigurator;
import com.googlesource.gerrit.plugins.reviewai.listener.NoLoggingConfigurator;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiAdministratorAccess;
import com.googlesource.gerrit.plugins.reviewai.permissions.NoAiAdministratorAccess;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewHistory;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewMessage;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewMessageStatus;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewThreads;
import com.googlesource.gerrit.plugins.reviewai.web.ReviewAgentConversations;
import com.googlesource.gerrit.plugins.reviewai.web.ReviewAgentModel;

/** Configures ReviewAI listeners, REST endpoints, and optional avatar integration. */
public class Module extends LifecycleModule {
  private final ReviewAiAvatarPluginDetector avatarPluginDetector;

  @Inject
  public Module(ReviewAiAvatarPluginDetector avatarPluginDetector) {
    this.avatarPluginDetector = avatarPluginDetector;
  }

  @Override
  protected void configure() {
    bind(AiAdministratorAccess.class).to(aiAdministratorAccessClass());
    bind(ClientCommandExtension.class).to(clientCommandExtensionClass());
    bind(LoggingConfigurator.class).to(loggingConfiguratorClass());
    // Gerrit's Prometheus exporter can only expose metrics after they have been registered with
    // MetricMaker. Most ReviewAI work happens inside event-scoped injectors that are created only
    // when Gerrit receives a review event, so relying on those injectors would hide the metric
    // names from /plugins/metrics-reporter-prometheus/metrics until the first event runs.
    // Register the metrics eagerly in the plugin-level injector so that, even if Gerrit triggers
    // no events after startup, Prometheus receives ReviewAI metrics with zero values rather than
    // no metrics.
    bind(ReviewAiMetrics.class).asEagerSingleton();
    if (avatarPluginDetector.isAvatarsGravatarAvailable()) {
      DynamicItem.bind(binder(), AvatarProvider.class).to(ReviewAiAvatarProvider.class);
    }
    listener().to(ReviewAiLifecycle.class);

    install(
        new RestApiModule() {
          @Override
          protected void configure() {
            get(ChangeResource.CHANGE_KIND, "ai-review-history").to(AiReviewHistory.class);
            get(ChangeResource.CHANGE_KIND, "ai-review-threads").to(AiReviewThreads.class);
            get(ChangeResource.CHANGE_KIND, "ai-review-agent-model").to(ReviewAgentModel.class);
            post(ChangeResource.CHANGE_KIND, "ai-review-message").to(AiReviewMessage.class);
            post(ChangeResource.CHANGE_KIND, "ai-review-message-status")
                .to(AiReviewMessageStatus.class);
            post(ChangeResource.CHANGE_KIND, "ai-review-agent-conversations")
                .to(ReviewAgentConversations.class);
          }
        });
  }

  protected Class<? extends AiAdministratorAccess> aiAdministratorAccessClass() {
    return NoAiAdministratorAccess.class;
  }

  protected Class<? extends ClientCommandExtension> clientCommandExtensionClass() {
    return DisabledClientCommandExtension.class;
  }

  protected Class<? extends LoggingConfigurator> loggingConfiguratorClass() {
    return NoLoggingConfigurator.class;
  }
}
