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

import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account.ReviewAiUser;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAgentRequestStatusStore;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import java.util.Optional;

class ReviewAgentEventRequestStatusUpdater {
  private final ChangeSetData changeSetData;
  private final GerritChange change;
  private final ReviewAgentRequestStatusStore statusStore;
  private final Localizer localizer;
  private final Configuration config;

  @Inject
  ReviewAgentEventRequestStatusUpdater(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      PluginDataHandlerProvider pluginDataHandlerProvider) {
    this.changeSetData = changeSetData;
    this.change = change;
    this.statusStore =
        new ReviewAgentRequestStatusStore(pluginDataHandlerProvider.getChangeScope());
    this.localizer = new Localizer(config);
    this.config = config;
  }

  PendingRequest getPendingRequest() {
    if (!(change.getPatchSetEvent() instanceof CommentAddedEvent)) {
      return PendingRequest.empty();
    }
    CommentAddedEvent event = (CommentAddedEvent) change.getPatchSetEvent();
    if (ReviewAiUser.matches(
        event.author == null ? null : event.author.get(),
        config.getUserId(),
        config.getGerritUserName(),
        config.getGerritUserEmail())) {
      return PendingRequest.empty();
    }
    return new PendingRequest(
        statusStore, statusStore.getLatestPendingRequestId(), localizer, changeSetData);
  }

  static class PendingRequest {
    private final ReviewAgentRequestStatusStore statusStore;
    private final Optional<String> requestId;
    private final Localizer localizer;
    private final ChangeSetData changeSetData;

    private PendingRequest(
        ReviewAgentRequestStatusStore statusStore,
        Optional<String> requestId,
        Localizer localizer,
        ChangeSetData changeSetData) {
      this.statusStore = statusStore;
      this.requestId = requestId;
      this.localizer = localizer;
      this.changeSetData = changeSetData;
    }

    static PendingRequest empty() {
      return new PendingRequest(null, Optional.empty(), null, null);
    }

    void completeNoUpdate() {
      if (requestId.isEmpty()) {
        return;
      }
      complete(
          SystemMessageFormatter.getPrefixedSystemMessage(
              localizer, localizer.getText("message.empty.review")));
    }

    void completeReview() {
      if (requestId.isEmpty()) {
        return;
      }
      if (changeSetData.getReviewSystemMessage() == null) {
        complete(null);
        return;
      }
      complete(
          SystemMessageFormatter.getPrefixedSystemMessage(
              localizer, changeSetData.getReviewSystemMessage()));
    }

    void fail(String responseText) {
      resolveRequestId().ifPresent(id -> statusStore.failed(id, responseText));
    }

    private void complete(String responseText) {
      resolveRequestId().ifPresent(id -> statusStore.completed(id, responseText));
    }

    private Optional<String> resolveRequestId() {
      return requestId.flatMap(statusStore::getPendingRequestId);
    }
  }
}
