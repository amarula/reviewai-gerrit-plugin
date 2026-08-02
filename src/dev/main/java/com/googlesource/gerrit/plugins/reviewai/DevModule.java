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

package com.googlesource.gerrit.plugins.reviewai;

import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.server.change.ChangeResource;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandExtension;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.DevClientCommandExtension;
import com.googlesource.gerrit.plugins.reviewai.avatar.ReviewAiAvatarPluginDetector;
import com.googlesource.gerrit.plugins.reviewai.listener.DevLoggingConfigurator;
import com.googlesource.gerrit.plugins.reviewai.listener.LoggingConfigurator;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiAdministratorAccess;
import com.googlesource.gerrit.plugins.reviewai.permissions.DevAiAdministratorAccess;
import com.googlesource.gerrit.plugins.reviewai.web.DevAiReviewConfig;

public class DevModule extends Module {
  @Inject
  public DevModule(ReviewAiAvatarPluginDetector avatarPluginDetector) {
    super(avatarPluginDetector);
  }

  @Override
  protected Class<? extends AiAdministratorAccess> aiAdministratorAccessClass() {
    return DevAiAdministratorAccess.class;
  }

  @Override
  protected Class<? extends ClientCommandExtension> clientCommandExtensionClass() {
    return DevClientCommandExtension.class;
  }

  @Override
  protected Class<? extends LoggingConfigurator> loggingConfiguratorClass() {
    return DevLoggingConfigurator.class;
  }

  @Override
  protected void configure() {
    super.configure();
    install(
        new RestApiModule() {
          @Override
          protected void configure() {
            get(ChangeResource.CHANGE_KIND, "ai-review-config").to(DevAiReviewConfig.class);
          }
        });
  }
}
