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

import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.googlesource.gerrit.plugins.reviewai.PatchSetReviewer;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@Slf4j
public class EventHandlerTypePatchSetReview implements IEventHandlerType {
  private final Configuration config;
  private final ChangeSetData changeSetData;
  private final GerritChange change;
  private final PatchSetReviewer reviewer;
  private final GerritClient gerritClient;

  EventHandlerTypePatchSetReview(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      PatchSetReviewer reviewer,
      GerritClient gerritClient) {
    this.config = config;
    this.changeSetData = changeSetData;
    this.change = change;
    this.reviewer = reviewer;
    this.gerritClient = gerritClient;
    log.debug(
        "Initialized EventHandlerTypePatchSetReview for full change ID: {}",
        change.getFullChangeId());
  }

  @Override
  public PreprocessResult preprocessEvent() {
    log.debug(
        "Starting preprocessing for patch set review on change ID: {}", change.getFullChangeId());
    if (!isPatchSetReviewEnabled(change)) {
      log.debug(
          "Patch set review is disabled or not applicable for change ID: {}",
          change.getFullChangeId());
      return PreprocessResult.EXIT;
    }
    gerritClient.retrievePatchSetInfo(change);
    log.debug("Patch set information retrieved for change ID: {}", change.getFullChangeId());
    return PreprocessResult.OK;
  }

  @Override
  public void processEvent() throws Exception {
    log.debug("Starting patch set review for change ID: {}", change.getFullChangeId());
    reviewer.review(change);
    log.debug("Completed patch set review for change ID: {}", change.getFullChangeId());
  }

  private boolean isPatchSetReviewEnabled(GerritChange change) {
    if (!config.getAiReviewPatchSet()) {
      log.debug("AI review of patch sets is disabled in configuration.");
      return false;
    }
    Optional<PatchSetAttribute> patchSetAttributeOptional = change.getPatchSetAttribute();
    if (patchSetAttributeOptional.isEmpty()) {
      log.info("No patch set attribute available for change ID: {}", change.getFullChangeId());
      return false;
    }
    PatchSetAttribute patchSetAttribute = patchSetAttributeOptional.get();
    ChangeKind patchSetEventKind = patchSetAttribute.kind;
    // The only Change kind that automatically triggers the review is REWORK. If review is forced
    // via command, this
    // condition is bypassed
    if (patchSetEventKind != ChangeKind.REWORK && !changeSetData.getForcedReview()) {
      log.debug(
          "Change kind '{}' is not REWORK and no forced review, for change ID: {}",
          patchSetEventKind,
          change.getFullChangeId());
      return false;
    }
    String authorUsername = patchSetAttribute.author.username;
    if (gerritClient.isDisabledUser(authorUsername)) {
      log.info(
          "Patch set review is disabled for user '{}', change ID: {}",
          authorUsername,
          change.getFullChangeId());
      return false;
    }
    if (gerritClient.isWorkInProgress(change)) {
      log.debug("Change is marked as Work In Progress for change ID: {}", change.getFullChangeId());
      return false;
    }
    return true;
  }
}
