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

package com.googlesource.gerrit.plugins.reviewai.review;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ChangeSetDataHandler;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.review.topic.TopicPatchSetReviewMerger;
import com.googlesource.gerrit.plugins.reviewai.review.topic.TopicReviewPatchSet;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class TopicPatchSetReviewer {
  private final Configuration config;
  private final GerritClient gerritClient;
  private final ChangeSetData changeSetData;
  private final Localizer localizer;
  private final PatchSetReviewer patchSetReviewer;
  private final TopicPatchSetReviewMerger topicPatchSetReviewMerger;

  TopicPatchSetReviewer(
      Configuration config,
      GerritClient gerritClient,
      ChangeSetData changeSetData,
      Localizer localizer,
      PatchSetReviewer patchSetReviewer) {
    this(
        config,
        gerritClient,
        changeSetData,
        localizer,
        patchSetReviewer,
        new TopicPatchSetReviewMerger());
  }

  TopicPatchSetReviewer(
      Configuration config,
      GerritClient gerritClient,
      ChangeSetData changeSetData,
      Localizer localizer,
      PatchSetReviewer patchSetReviewer,
      TopicPatchSetReviewMerger topicPatchSetReviewMerger) {
    this.config = config;
    this.gerritClient = gerritClient;
    this.changeSetData = changeSetData;
    this.localizer = localizer;
    this.patchSetReviewer = patchSetReviewer;
    this.topicPatchSetReviewMerger = topicPatchSetReviewMerger;
  }

  void review(List<GerritChange> changes) throws Exception {
    review(changes, false);
  }

  void review(List<GerritChange> changes, boolean includeAiFailureDetails) throws Exception {
    log.debug("Starting topic review process for {} changes", changes.size());
    List<TopicReviewPatchSet> patchSets = new ArrayList<>();
    changeSetData.setReviewRepeatedCommentsMessage(null);
    for (GerritChange topicChange : changes) {
      gerritClient.requireCurrentRevision(topicChange);
      String patchSet = gerritClient.getPatchSet(topicChange);
      if (!patchSetReviewer.shouldSkipAiReviewForEmptyPatchSet(topicChange)) {
        patchSets.add(topicPatchSetReviewMerger.patchSet(topicChange, patchSets.size(), patchSet));
      }
    }
    if (patchSets.size() < 2) {
      if (patchSets.isEmpty()) {
        log.debug("No topic patch sets remain after patch filtering.");
        return;
      }
      patchSetReviewer.review(patchSets.getFirst().change(), includeAiFailureDetails);
      return;
    }

    GerritChange primaryChange = patchSets.getFirst().change();
    gerritClient.getPatchSet(primaryChange);
    ChangeSetDataHandler.update(config, primaryChange, gerritClient, changeSetData, localizer);
    AiResponseContent reviewReply = null;
    try {
      reviewReply =
          patchSetReviewer.getReviewReply(
              primaryChange, topicPatchSetReviewMerger.buildMergedPatchSet(patchSets));
      log.debug("AI final response for topic review: {}", reviewReply);
    } catch (AiRequestSupersededException e) {
      throw e;
    } catch (Exception e) {
      log.error(
          "AI request failed for topic review rooted at `{}`. domain=`{}`, model=`{}`. Cause: {}",
          primaryChange.getFullChangeId(),
          config.getAiDomain(),
          config.getAiModel(),
          e.getMessage(),
          e);
      String publicErrorMessage =
          SystemMessageFormatter.getLocalizedErrorMessage(
              localizer, "message.openai.connection.error");
      changeSetData.setReviewSystemMessage(publicErrorMessage);
      changeSetData.setReviewStatusMessage(
          includeAiFailureDetails
              ? SystemMessageFormatter.getLocalizedErrorMessageWithReason(
                  localizer, "message.openai.connection.error", e)
              : publicErrorMessage);
    }

    if (reviewReply == null && changeSetData.getReviewSystemMessage() == null) {
      log.debug("Skipping Gerrit topic review publication because no AI review was performed.");
      return;
    }

    List<Double> topicReviewScores = patchSetReviewer.getReviewScores(reviewReply);
    for (TopicReviewPatchSet patchSet : patchSets) {
      gerritClient.requireCurrentRevision(patchSet.change());
    }
    for (TopicReviewPatchSet patchSet : patchSets) {
      patchSetReviewer.publishTopicReviewPart(
          reviewReply, patchSet.change(), patchSet.prefix(), topicReviewScores);
    }
  }
}
