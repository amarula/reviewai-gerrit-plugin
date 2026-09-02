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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.AiRequestCancellation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.StalePatchSetException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType;
import com.googlesource.gerrit.plugins.reviewai.listener.EventHandlerTask.Result;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewer;
import lombok.extern.slf4j.Slf4j;

/** Owns the execution state produced by preprocessing one event handler task. */
@Slf4j
final class PreparedEventHandlerTask {
  private final AiRequestIntakeDecision decision;
  private final String sourceEventId;
  private final IEventHandlerType eventHandlerType;
  private final GerritChange change;
  private final ChangeSetData changeSetData;
  private final PatchSetReviewer reviewer;
  private final boolean administratorUser;
  private final ReviewAgentEventRequestStatusUpdater.PendingRequest pendingRequest;
  private final ReviewAiMetrics metrics;
  private final Localizer localizer;

  PreparedEventHandlerTask(
      AiRequestIntakeDecision decision,
      String sourceEventId,
      IEventHandlerType eventHandlerType,
      GerritChange change,
      ChangeSetData changeSetData,
      PatchSetReviewer reviewer,
      boolean administratorUser,
      ReviewAgentEventRequestStatusUpdater.PendingRequest pendingRequest,
      ReviewAiMetrics metrics,
      Localizer localizer) {
    this.decision = decision;
    this.sourceEventId = sourceEventId;
    this.eventHandlerType = eventHandlerType;
    this.change = change;
    this.changeSetData = changeSetData;
    this.reviewer = reviewer;
    this.administratorUser = administratorUser;
    this.pendingRequest = pendingRequest;
    this.metrics = metrics;
    this.localizer = localizer;
  }

  AiRequestIntakeDecision decision() {
    return decision;
  }

  String sourceEventId() {
    return sourceEventId;
  }

  Result execute() {
    return execute(eventHandlerType::processEvent);
  }

  Result reject() {
    changeSetData.setReviewSystemMessage(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer, "message.ai.request.in.progress"));
    return execute(() -> reviewer.review(change, administratorUser));
  }

  void discard() {
    pendingRequest.completeNoUpdate();
  }

  private Result execute(EventProcessor processor) {
    ReviewAiMetrics.MetricTimer reviewRunTimer = metrics.startReviewRun(change.getEventType());
    AiRequestCancellation cancellation = AiRequestCancellation.current();
    changeSetData.setAiRequestCancellation(cancellation);
    try (AiRequestCancellation.Work ignored = cancellation.beginWork()) {
      cancellation.throwIfSupersessionRequested();
      log.debug("Processing event for change ID:: {}", change.getFullChangeId());
      processor.process();
      log.debug("Finished processing event for change ID: {}", change.getFullChangeId());
      reviewRunTimer.complete();
    } catch (StalePatchSetException | AiRequestSupersededException e) {
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

  @FunctionalInterface
  private interface EventProcessor {
    void process() throws Exception;
  }
}
