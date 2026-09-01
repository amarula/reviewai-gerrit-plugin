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

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.entities.Account;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiAdministratorAccess;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewer;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewPermission;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackPublisher;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.StalePatchSetException;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

@Slf4j
public class EventHandlerTask implements Runnable {
  @VisibleForTesting
  public enum Result {
    OK,
    NOT_SUPPORTED,
    SUPERSEDED,
    FAILURE
  }

  public enum SupportedEvents {
    PATCH_SET_CREATED,
    COMMENT_ADDED
  }

  public static final Map<SupportedEvents, Class<?>> EVENT_CLASS_MAP =
      Map.of(
          SupportedEvents.PATCH_SET_CREATED, PatchSetCreatedEvent.class,
          SupportedEvents.COMMENT_ADDED, CommentAddedEvent.class);

  private static final Map<String, SupportedEvents> EVENT_TYPE_MAP =
      Map.of(
          "patchset-created", SupportedEvents.PATCH_SET_CREATED,
          "comment-added", SupportedEvents.COMMENT_ADDED);

  private final Configuration config;
  private final GerritClient gerritClient;
  private final ChangeSetData changeSetData;
  private final GerritChange change;
  private final PatchSetReviewer reviewer;
  private final AiReviewPermission aiReviewPermission;
  private final IdentifiedUser.GenericFactory identifiedUserFactory;
  private final AccountCache accountCache;
  private final AiAdministratorAccess aiAdministratorAccess;
  private final ReviewAgentEventRequestStatusUpdater reviewAgentRequestStatusUpdater;
  private final TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator;
  private final AiReviewApplicabilityChecker aiReviewApplicabilityChecker;
  private final ReviewAiMetrics metrics;
  private final ReviewFeedbackPublisher reviewFeedbackPublisher;
  private final Localizer localizer;

  private SupportedEvents processing_event_type;
  private IEventHandlerType eventHandlerType;
  private CurrentUser eventUser;
  private ReviewAgentEventRequestStatusUpdater.PendingRequest pendingRequest;
  private String sourceEventId;
  private boolean preparationAttempted;
  private boolean prepared;
  private boolean commentAddressed;

  @Inject
  EventHandlerTask(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      PatchSetReviewer reviewer,
      GerritClient gerritClient,
      AiReviewPermission aiReviewPermission,
      IdentifiedUser.GenericFactory identifiedUserFactory,
      AccountCache accountCache,
      ReviewAgentEventRequestStatusUpdater reviewAgentRequestStatusUpdater,
      TopicPatchSetReviewCoordinator topicPatchSetReviewCoordinator,
      AiReviewApplicabilityChecker aiReviewApplicabilityChecker,
      EventBuildFeatures buildFeatures,
      ReviewAiMetrics metrics,
      ReviewFeedbackPublisher reviewFeedbackPublisher,
      Localizer localizer) {
    this.changeSetData = changeSetData;
    this.change = change;
    this.reviewer = reviewer;
    this.gerritClient = gerritClient;
    this.config = config;
    this.aiReviewPermission = aiReviewPermission;
    this.identifiedUserFactory = identifiedUserFactory;
    this.accountCache = accountCache;
    this.reviewAgentRequestStatusUpdater = reviewAgentRequestStatusUpdater;
    this.topicPatchSetReviewCoordinator = topicPatchSetReviewCoordinator;
    this.aiReviewApplicabilityChecker = aiReviewApplicabilityChecker;
    this.aiAdministratorAccess = buildFeatures.aiAdministratorAccess();
    this.metrics = metrics;
    this.reviewFeedbackPublisher = reviewFeedbackPublisher;
    this.localizer = localizer;
    log.debug("EventHandlerTask initialized for change ID: {}", change.getFullChangeId());
  }

  @Override
  public void run() {
    log.debug("EventHandlerTask started for event type: {}", change.getEventType());
    Result result = execute();
    log.debug("EventHandlerTask execution completed with result: {}", result);
  }

  @VisibleForTesting
  public Result execute() {
    return execute(null);
  }

  public Result execute(String requestedSourceEventId) {
    Preparation preparation = prepareForIntake(requestedSourceEventId);
    return preparation.decision().disposition() == AiRequestIntakeDecision.Disposition.IGNORE
        ? Result.NOT_SUPPORTED
        : executePrepared();
  }

  public Preparation prepareForIntake(String requestedSourceEventId) {
    if (preparationAttempted) {
      throw new IllegalStateException("Event handler task is already prepared");
    }
    preparationAttempted = true;
    log.debug("Starting event processing for change ID: {}", change.getFullChangeId());
    sourceEventId = requestedSourceEventId;
    if (!preProcessEvent()) {
      pendingRequest = reviewAgentRequestStatusUpdater.getPendingRequest(sourceEventId);
      log.debug(
          "Preprocessing event not supported or failed for event type: {}", change.getEventType());
      pendingRequest.completeNoUpdate();
      return new Preparation(AiRequestIntakeDecision.ignored(), sourceEventId);
    }
    pendingRequest = reviewAgentRequestStatusUpdater.getPendingRequest(sourceEventId);
    prepared = true;
    return new Preparation(classify(), sourceEventId);
  }

  public Result executePrepared() {
    return executePrepared(eventHandlerType::processEvent);
  }

  public Result rejectPrepared() {
    changeSetData.setReviewSystemMessage(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer, "message.ai.request.in.progress"));
    return executePrepared(() -> reviewer.review(change, isAdministratorUser(eventUser)));
  }

  public void failPendingRequest(String requestedSourceEventId) {
    reviewAgentRequestStatusUpdater
        .getPendingRequest(requestedSourceEventId)
        .fail(
            SystemMessageFormatter.getLocalizedWarningMessage(
                localizer, "message.ai.request.interrupted"));
  }

  private Result executePrepared(EventProcessor processor) {
    if (!prepared) {
      throw new IllegalStateException("Event handler task must be prepared before execution");
    }
    ReviewAiMetrics.MetricTimer reviewRunTimer = metrics.startReviewRun(change.getEventType());
    try {
      log.debug("Processing event for change ID:: {}", change.getFullChangeId());
      processor.process();
      log.debug("Finished processing event for change ID: {}", change.getFullChangeId());
      reviewRunTimer.complete();
    } catch (StalePatchSetException e) {
      reviewRunTimer.complete();
      log.info(
          "Skipping superseded patch set review for {}: {}",
          change.getFullChangeId(),
          e.getMessage());
      pendingRequest.completeNoUpdate();
      return Result.SUPERSEDED;
    } catch (Exception e) {
      reviewRunTimer.fail();
      log.error("Error while processing event for change ID: {}", change.getFullChangeId(), e);
      pendingRequest.fail(e.getMessage());
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return Result.FAILURE;
    }
    pendingRequest.completeReview();
    return Result.OK;
  }

  public void discardPrepared() {
    if (pendingRequest != null) {
      pendingRequest.completeNoUpdate();
    }
  }

  private boolean preProcessEvent() {
    String eventType = Optional.ofNullable(change.getEventType()).orElse("");
    processing_event_type = EVENT_TYPE_MAP.get(eventType);
    if (processing_event_type == null) {
      log.debug("Event type not supported: {}", eventType);
      return false;
    }
    eventUser = getEventUser();
    if (!isReviewEnabled(change)) {
      log.debug("Review not enabled for event type: {}", eventType);
      return false;
    }

    while (true) {
      eventHandlerType = getEventHandlerType();
      log.debug("Event handler type resolved for event: {}", eventType);
      IEventHandlerType.PreprocessResult preprocessResult = eventHandlerType.preprocessEvent();
      captureCommentEventContext();
      switch (preprocessResult) {
        case EXIT -> {
          log.debug("Exiting event handler preprocessing for event type: {}", eventType);
          return false;
        }
        case SWITCH_TO_PATCH_SET_CREATED -> {
          log.debug("Switching to patch set created event type");
          processing_event_type = SupportedEvents.PATCH_SET_CREATED;
          continue;
        }
      }
      break;
    }
    log.debug("Preprocessing completed successfully for event type: {}", eventType);
    return true;
  }

  private AiRequestIntakeDecision classify() {
    if (change.getPatchSetEvent() instanceof PatchSetCreatedEvent) {
      return AiRequestIntakeClassifier.patchSetReview();
    }
    return AiRequestIntakeClassifier.comment(
        commentAddressed,
        Boolean.TRUE.equals(changeSetData.getDeferredReview()),
        changeSetData);
  }

  private void captureCommentEventContext() {
    if (!(change.getPatchSetEvent() instanceof CommentAddedEvent)) {
      return;
    }
    GerritClientData clientData = gerritClient.getClientData(change);
    CommentData commentData = clientData == null ? null : clientData.getCommentData();
    if (commentData != null) {
      if (sourceEventId == null) {
        sourceEventId = commentData.getSourceChangeMessageId();
      }
      commentAddressed =
          commentAddressed
              || commentData.getAddressedComments() != null
                  && !commentData.getAddressedComments().isEmpty();
    }
  }

  private IEventHandlerType getEventHandlerType() {
    boolean administratorUser = isAdministratorUser(eventUser);
    return switch (processing_event_type) {
      case PATCH_SET_CREATED ->
          new EventHandlerTypePatchSetReview(
              config,
              changeSetData,
              change,
              reviewer,
              gerritClient,
              topicPatchSetReviewCoordinator,
              aiReviewApplicabilityChecker,
              administratorUser);
      case COMMENT_ADDED ->
          new EventHandlerTypeCommentAdded(
              config,
              changeSetData,
              change,
              reviewer,
              gerritClient,
              aiReviewApplicabilityChecker,
              reviewFeedbackPublisher,
              administratorUser,
              sourceEventId);
    };
  }

  private boolean isAdministratorUser(CurrentUser user) {
    return aiAdministratorAccess.isAdministrator(config, user);
  }

  private boolean isReviewEnabled(GerritChange change) {
    if (!aiReviewPermission.isAiReviewConfigured(change.getProjectNameKey())) {
      log.debug(
          "Project {} has no AI review configuration; skipping review for change {}",
          change.getProjectNameKey(),
          change.getFullChangeId());
      return false;
    }

    if (eventUser != null
        && aiReviewPermission.isAiReviewExplicitlyDisallowed(
            change.getProjectNameKey(), change.getBranchNameKey().branch(), eventUser)) {
      log.debug(
          "AI review access is explicitly denied for project {} and branch {}",
          change.getProjectNameKey(),
          change.getBranchNameKey());
      return false;
    }

    return true;
  }

  private CurrentUser getEventUser() {
    Optional<AccountAttribute> eventAccount = getEventAccount();
    if (eventAccount.isEmpty()) {
      return null;
    }

    AccountAttribute account = eventAccount.get();
    if (account.accountId != null) {
      return identifiedUserFactory.create(Account.id(account.accountId));
    }
    return Optional.ofNullable(account.username)
        .flatMap(accountCache::getByUsername)
        .map(identifiedUserFactory::create)
        .orElse(null);
  }

  private Optional<AccountAttribute> getEventAccount() {
    try {
      return switch (processing_event_type) {
        case COMMENT_ADDED ->
            Optional.ofNullable(((CommentAddedEvent) change.getPatchSetEvent()).author.get());
        case PATCH_SET_CREATED ->
            Optional.ofNullable(((PatchSetCreatedEvent) change.getPatchSetEvent()).uploader.get());
      };
    } catch (RuntimeException e) {
      log.debug("Failed to retrieve event account for change {}", change.getFullChangeId(), e);
      return Optional.empty();
    }
  }

  public record Preparation(AiRequestIntakeDecision decision, String sourceEventId) {}

  @FunctionalInterface
  private interface EventProcessor {
    void process() throws Exception;
  }

}
