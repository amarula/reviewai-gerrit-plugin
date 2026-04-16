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

import com.google.gerrit.extensions.restapi.RestApiModule;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.events.EventListener;
import com.google.inject.AbstractModule;
import com.google.inject.multibindings.Multibinder;
import com.googlesource.gerrit.plugins.reviewai.listener.GerritListener;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewHistory;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewMessage;
import com.googlesource.gerrit.plugins.reviewai.web.ReviewAgentConversations;
import com.googlesource.gerrit.plugins.reviewai.web.ReviewAgentModel;

public class Module extends AbstractModule {
  @Override
  protected void configure() {
    Multibinder<EventListener> eventListenerBinder =
        Multibinder.newSetBinder(binder(), EventListener.class);
    eventListenerBinder.addBinding().to(GerritListener.class);

    install(
        new RestApiModule() {
          @Override
          protected void configure() {
            get(ChangeResource.CHANGE_KIND, "ai-review-history").to(AiReviewHistory.class);
            get(ChangeResource.CHANGE_KIND, "ai-review-agent-model").to(ReviewAgentModel.class);
            post(ChangeResource.CHANGE_KIND, "ai-review-message").to(AiReviewMessage.class);
            post(ChangeResource.CHANGE_KIND, "ai-review-agent-conversations")
                .to(ReviewAgentConversations.class);
          }
        });
  }
}
