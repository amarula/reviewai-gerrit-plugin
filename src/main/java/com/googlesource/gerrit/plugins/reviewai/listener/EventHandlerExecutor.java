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

package com.googlesource.gerrit.plugins.reviewai.listener;

import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.events.PatchSetEvent;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandExtension;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.listener.GerritEventHandlerContextFactory.Context;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiAdministratorAccess;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class EventHandlerExecutor {
  private final AiRequestDispatcher dispatcher;
  private final GerritEventHandlerContextFactory contextFactory;
  private final TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator;

  @Inject
  EventHandlerExecutor(
      Injector injector,
      AiRequestCoordinator coordinator,
      ConfigCreator configCreator,
      TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator,
      AiAdministratorAccess aiAdministratorAccess,
      ClientCommandExtension clientCommandExtension) {
    this.topicPatchSetReviewCoordinator = topicPatchSetReviewCoordinator;
    this.contextFactory =
        new GerritEventHandlerContextFactory(
            injector, new EventBuildFeatures(aiAdministratorAccess, clientCommandExtension));
    this.dispatcher =
        new AiRequestDispatcher(
            coordinator, configCreator, topicPatchSetReviewCoordinator, contextFactory);
  }

  public void start() {
    dispatcher.start();
  }

  public void stop() {
    dispatcher.stop();
  }

  public void execute(Configuration config, Event event) {
    log.debug("Executing event handler for event: {}", event);
    Context context = contextFactory.create(config, event);
    if (event instanceof PatchSetCreatedEvent patchSetCreatedEvent) {
      long patchSetNumber =
          new GerritChange(event)
              .getPatchSetAttribute()
              .map(patchSet -> (long) patchSet.number)
              .orElse(0L);
      dispatcher.requestActiveReviewSupersession(
          context, config, patchSetCreatedEvent, patchSetNumber);
      topicPatchSetReviewCoordinator.recordEvent(patchSetCreatedEvent);
    }
    dispatcher.submit(context, config, (PatchSetEvent) event);
    log.debug("Task submitted to executor for event: {}", event);
  }
}
