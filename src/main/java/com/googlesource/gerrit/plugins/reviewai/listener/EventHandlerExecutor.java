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

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.Injector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandExtension;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequest;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestStore;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestSubmission;
import com.googlesource.gerrit.plugins.reviewai.listener.AiRequestCoordinator.ProcessingOutcome;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiAdministratorAccess;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class EventHandlerExecutor {
  private final Injector injector;
  private final AiRequestCoordinator coordinator;
  private final ConfigCreator configCreator;
  private final TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator;
  private final EventBuildFeatures buildFeatures;

  @Inject
  EventHandlerExecutor(
      Injector injector,
      AiRequestCoordinator coordinator,
      ConfigCreator configCreator,
      TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator,
      AiAdministratorAccess aiAdministratorAccess,
      ClientCommandExtension clientCommandExtension) {
    this.injector = injector;
    this.coordinator = coordinator;
    this.configCreator = configCreator;
    this.topicPatchSetReviewCoordinator = topicPatchSetReviewCoordinator;
    this.buildFeatures = new EventBuildFeatures(aiAdministratorAccess, clientCommandExtension);
  }

  public void start() {
    coordinator.start(this::processPersistedRequest, this::recoverAbandonedRequest);
  }

  public void stop() {
    coordinator.stop();
  }

  public void execute(Configuration config, Event event) {
    log.debug("Executing event handler for event: {}", event);
    Injector eventInjector = createEventInjector(config, event);
    if (event instanceof PatchSetCreatedEvent patchSetCreatedEvent) {
      long patchSetNumber =
          new GerritChange(event)
              .getPatchSetAttribute()
              .map(patchSet -> (long) patchSet.number)
              .orElse(0L);
      requestActiveReviewSupersession(
          eventInjector, config, patchSetCreatedEvent, patchSetNumber);
      topicPatchSetReviewCoordinator.recordEvent(patchSetCreatedEvent);
    }
    coordinator.submitIntake(() -> intake(eventInjector, config, (PatchSetEvent) event));
  }

  private void requestActiveReviewSupersession(
      Injector eventInjector,
      Configuration config,
      PatchSetEvent event,
      Long newerPatchSetNumber) {
    GerritChange currentChange = new GerritChange(event);
    String changeId = currentChange.getFullChangeId();
    Optional<AiRequest> requested =
        newerPatchSetNumber == null
            ? coordinator.requestReviewSupersession(
                changeId, AiRequestCoordinator.STATE_CHANGE_SUPERSESSION_REASON)
            : coordinator.requestReviewSupersession(changeId, newerPatchSetNumber);
    requested
        .ifPresent(
            request -> {
              try {
                eventInjector
                    .getInstance(ReviewAgentEventRequestStatusUpdater.class)
                    .completeSupersededRequest(request, newerPatchSetNumber);
              } catch (Exception e) {
                log.error(
                    "Could not complete sidebar request status for superseded AI request {}",
                    request.requestId(),
                    e);
              }
              try {
                eventInjector
                    .getInstance(SupersededReviewNotifier.class)
                    .publish(config, currentChange, request, newerPatchSetNumber);
              } catch (Exception e) {
                log.error(
                    "Could not report early supersession of AI request {}",
                    request.requestId(),
                    e);
              }
            });
  }

  private void intake(
      Injector eventInjector, Configuration config, PatchSetEvent event) {
    EventHandlerTask task = eventInjector.getInstance(EventHandlerTask.class);
    try {
      EventHandlerTask.Preparation preparation = task.prepareForIntake(null);
      AiRequestIntakeDecision decision = preparation.decision();
      if (decision.supersedesActiveReview()) {
        requestActiveReviewSupersession(eventInjector, config, event, null);
      }
      switch (decision.disposition()) {
        case IGNORE -> log.debug("Ignoring event {} after preprocessing", event.getType());
        case DIRECT -> task.executePrepared();
        case PERSIST -> admit(event, task, preparation, decision);
      }
    } catch (RuntimeException e) {
      log.error("Failed to intake event {}", event.getType(), e);
      task.discardPrepared();
    }
  }

  private void admit(
      PatchSetEvent event,
      EventHandlerTask task,
      EventHandlerTask.Preparation preparation,
      AiRequestIntakeDecision decision) {
    String sourceEventId = resolveSourceEventId(event, preparation.sourceEventId());
    AiRequestDescriptor descriptor = AiRequestDescriptor.from(event, sourceEventId);
    AiRequestSubmission submission =
        new AiRequestSubmission(
            UUID.randomUUID().toString(),
            new GerritChange(event).getFullChangeId(),
            sourceEventId,
            decision.kind(),
            decision.admissionPolicy(),
            descriptor.toJson());
    AiRequestStore.Admission admission =
        coordinator.admit(submission, ignored -> processingOutcome(task.executePrepared()));
    if (admission.duplicate()) {
      task.discardPrepared();
    } else if (admission.request().state() == AiRequest.State.REJECTED) {
      if (preparation.sourceEventId() == null) {
        task.discardPrepared();
      } else {
        requireSuccessful(task.rejectPrepared());
      }
    }
    log.debug(
        "AI request {} admitted with state {}",
        admission.request().requestId(),
        admission.request().state());
  }

  private ProcessingOutcome processPersistedRequest(AiRequest request) throws Exception {
    AiRequestDescriptor descriptor = AiRequestDescriptor.fromJson(request.payloadJson());
    PatchSetEvent event = descriptor.toEvent();
    if (event instanceof PatchSetCreatedEvent patchSetCreatedEvent) {
      topicPatchSetReviewCoordinator.recordEvent(patchSetCreatedEvent);
    }
    Configuration config =
        configCreator.createConfig(
            Project.nameKey(descriptor.project()), Change.key(descriptor.changeKey()));
    return processingOutcome(createTask(config, event).execute(descriptor.sourceEventId()));
  }

  private void recoverAbandonedRequest(AiRequest request) throws Exception {
    AiRequestDescriptor descriptor = AiRequestDescriptor.fromJson(request.payloadJson());
    Configuration config =
        configCreator.createConfig(
            Project.nameKey(descriptor.project()), Change.key(descriptor.changeKey()));
    createTask(config, descriptor.toEvent()).failPendingRequest(descriptor.sourceEventId());
  }

  private EventHandlerTask createTask(Configuration config, Event event) {
    return createEventInjector(config, event).getInstance(EventHandlerTask.class);
  }

  private Injector createEventInjector(Configuration config, Event event) {
    return injector.createChildInjector(
        new GerritEventContextModule(config, event, buildFeatures));
  }

  private static String resolveSourceEventId(PatchSetEvent event, String sourceEventId) {
    if (sourceEventId != null && !sourceEventId.isBlank()) {
      return sourceEventId;
    }
    return String.join(
        ":",
        new GerritChange(event).getPatchSetEventKey(),
        event.getType(),
        String.valueOf(event.eventCreatedOn));
  }

  private static void requireSuccessful(EventHandlerTask.Result result) {
    if (result == EventHandlerTask.Result.FAILURE) {
      throw new IllegalStateException("Gerrit event handler failed");
    }
  }

  private static ProcessingOutcome processingOutcome(EventHandlerTask.Result result) {
    return switch (result) {
      case OK, NOT_SUPPORTED -> ProcessingOutcome.COMPLETED;
      case SUPERSEDED -> ProcessingOutcome.SUPERSEDED;
      case FAILURE -> throw new IllegalStateException("Gerrit event handler failed");
    };
  }
}
