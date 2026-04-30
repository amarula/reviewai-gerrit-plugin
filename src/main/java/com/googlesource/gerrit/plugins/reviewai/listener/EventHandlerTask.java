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
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.PatchSetReviewer;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.web.AiReviewPermission;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.Optional;

@Slf4j
public class EventHandlerTask implements Runnable {
  @VisibleForTesting
  public enum Result {
    OK,
    NOT_SUPPORTED,
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
  private final ReviewAgentEventRequestStatusUpdater reviewAgentRequestStatusUpdater;

  private SupportedEvents processing_event_type;
  private IEventHandlerType eventHandlerType;

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
      ReviewAgentEventRequestStatusUpdater reviewAgentRequestStatusUpdater) {
    this.changeSetData = changeSetData;
    this.change = change;
    this.reviewer = reviewer;
    this.gerritClient = gerritClient;
    this.config = config;
    this.aiReviewPermission = aiReviewPermission;
    this.identifiedUserFactory = identifiedUserFactory;
    this.accountCache = accountCache;
    this.reviewAgentRequestStatusUpdater = reviewAgentRequestStatusUpdater;
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
    log.debug("Starting event processing for change ID: {}", change.getFullChangeId());
    ReviewAgentEventRequestStatusUpdater.PendingRequest reviewAgentRequest =
        reviewAgentRequestStatusUpdater.getPendingRequest();
    if (!preProcessEvent()) {
      log.debug(
          "Preprocessing event not supported or failed for event type: {}", change.getEventType());
      reviewAgentRequest.completeNoUpdate();
      return Result.NOT_SUPPORTED;
    }

    try {
      log.info("Processing event for change ID:: {}", change.getFullChangeId());
      eventHandlerType.processEvent();
      log.info("Finished processing event for change ID: {}", change.getFullChangeId());
    } catch (Exception e) {
      log.error("Error while processing event for change ID: {}", change.getFullChangeId(), e);
      reviewAgentRequest.fail(e.getMessage());
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return Result.FAILURE;
    }
    reviewAgentRequest.completeReview();
    return Result.OK;
  }

  private boolean preProcessEvent() {
    String eventType = Optional.ofNullable(change.getEventType()).orElse("");
    processing_event_type = EVENT_TYPE_MAP.get(eventType);
    if (processing_event_type == null) {
      log.debug("Event type not supported: {}", eventType);
      return false;
    }

    if (!isReviewEnabled(change)) {
      log.debug("Review not enabled for event type: {}", eventType);
      return false;
    }

    while (true) {
      eventHandlerType = getEventHandlerType();
      log.debug("Event handler type resolved for event: {}", eventType);
      switch (eventHandlerType.preprocessEvent()) {
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

  private IEventHandlerType getEventHandlerType() {
    return switch (processing_event_type) {
      case PATCH_SET_CREATED ->
          new EventHandlerTypePatchSetReview(config, changeSetData, change, reviewer, gerritClient);
      case COMMENT_ADDED ->
          new EventHandlerTypeCommentAdded(changeSetData, change, reviewer, gerritClient);
    };
  }

  private boolean isReviewEnabled(GerritChange change) {
    CurrentUser eventUser = getEventUser();
    if (eventUser != null
        && aiReviewPermission.isAiReviewExplicitlyDisallowed(
            change.getProjectNameKey(), change.getBranchNameKey().branch(), eventUser)) {
      log.debug(
          "AI review access is explicitly denied for project {} and branch {}",
          change.getProjectNameKey(),
          change.getBranchNameKey());
      return false;
    }

    String topic = getTopic(change).orElse("");
    log.debug("PatchSet Topic retrieved: '{}'", topic);
    if (gerritClient.isDisabledTopic(topic)) {
      log.info("Review disabled for topic: '{}'", topic);
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

  private Optional<String> getTopic(GerritChange change) {
    try {
      ChangeAttribute changeAttribute = change.getPatchSetEvent().change.get();
      return Optional.ofNullable(changeAttribute.topic);
    } catch (NullPointerException e) {
      log.debug("Failed to retrieve topic for change ID: {}", change.getFullChangeId());
      return Optional.empty();
    }
  }
}
