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

package com.googlesource.gerrit.plugins.reviewai.listener;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.events.PatchSetEvent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequest;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestStore;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestSubmission;
import com.googlesource.gerrit.plugins.reviewai.listener.AiRequestCoordinator.ProcessingOutcome;
import com.googlesource.gerrit.plugins.reviewai.listener.GerritEventHandlerContextFactory.Context;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/** Admits prepared event tasks and reconstructs durable requests for execution. */
@Slf4j
final class AiRequestDispatcher {
  private final AiRequestCoordinator coordinator;
  private final ConfigCreator configCreator;
  private final TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator;
  private final GerritEventHandlerContextFactory contextFactory;

  AiRequestDispatcher(
      AiRequestCoordinator coordinator,
      ConfigCreator configCreator,
      TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator,
      GerritEventHandlerContextFactory contextFactory) {
    this.coordinator = coordinator;
    this.configCreator = configCreator;
    this.topicPatchSetReviewCoordinator = topicPatchSetReviewCoordinator;
    this.contextFactory = contextFactory;
  }

  void start() {
    coordinator.start(this::processPersistedRequest, this::recoverAbandonedRequest);
  }

  void stop() {
    coordinator.stop();
  }

  void submit(Context context, Configuration config, PatchSetEvent event) {
    coordinator.submitIntake(() -> intake(context, config, event));
  }

  void requestActiveReviewSupersession(
      Context context,
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
                context
                    .injector()
                    .getInstance(ReviewAgentEventRequestStatusUpdater.class)
                    .completeSupersededRequest(request, newerPatchSetNumber);
              } catch (Exception e) {
                log.error(
                    "Could not complete sidebar request status for superseded AI request {}",
                    request.requestId(),
                    e);
              }
              try {
                context
                    .injector()
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

  private void intake(Context context, Configuration config, PatchSetEvent event) {
    PreparedEventHandlerTask preparedTask = null;
    try {
      preparedTask = context.task().prepareForIntake(null);
      AiRequestIntakeDecision decision = preparedTask.decision();
      if (decision.supersedesActiveReview()) {
        requestActiveReviewSupersession(context, config, event, null);
      }
      switch (decision.disposition()) {
        case IGNORE -> {
          log.debug("Ignoring event {} after preprocessing", event.getType());
          preparedTask.discard();
        }
        case DIRECT -> preparedTask.execute();
        case PERSIST -> admit(event, preparedTask, decision);
      }
    } catch (RuntimeException e) {
      log.error("Failed to intake event {}", event.getType(), e);
      if (preparedTask != null) {
        preparedTask.discard();
      }
    }
  }

  private void admit(
      PatchSetEvent event,
      PreparedEventHandlerTask preparedTask,
      AiRequestIntakeDecision decision) {
    String sourceEventId = resolveSourceEventId(event, preparedTask.sourceEventId());
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
        coordinator.admit(submission, ignored -> processingOutcome(preparedTask.execute()));
    if (admission.duplicate()) {
      preparedTask.discard();
    } else if (admission.request().state() == AiRequest.State.REJECTED) {
      if (preparedTask.sourceEventId() == null) {
        preparedTask.discard();
      } else {
        requireSuccessful(preparedTask.reject());
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
    EventHandlerTask task = contextFactory.create(config, event).task();
    return processingOutcome(task.execute(descriptor.sourceEventId()));
  }

  private void recoverAbandonedRequest(AiRequest request) throws Exception {
    AiRequestDescriptor descriptor = AiRequestDescriptor.fromJson(request.payloadJson());
    Configuration config =
        configCreator.createConfig(
            Project.nameKey(descriptor.project()), Change.key(descriptor.changeKey()));
    Context context = contextFactory.create(config, descriptor.toEvent());
    context
        .injector()
        .getInstance(ReviewAgentEventRequestStatusUpdater.class)
        .getPendingRequest(descriptor.sourceEventId())
        .fail(
            SystemMessageFormatter.getLocalizedWarningMessage(
                context.injector().getInstance(Localizer.class),
                "message.ai.request.interrupted"));
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
