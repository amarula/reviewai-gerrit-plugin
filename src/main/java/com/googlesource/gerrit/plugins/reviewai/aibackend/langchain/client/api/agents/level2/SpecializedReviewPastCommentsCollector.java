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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritAiReviewHistoryCollector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiHistoryMessageFilter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SpecializedReviewPastCommentsCollector {
  private final Configuration config;
  private final GerritClient gerritClient;
  private final Localizer localizer;
  private final GerritAiReviewHistoryCollector aiReviewHistoryCollector;
  private final AiHistoryMessageFilter aiHistoryMessageFilter;

  SpecializedReviewPastCommentsCollector(
      Configuration config, GerritClient gerritClient, Localizer localizer) {
    this.config = config;
    this.gerritClient = gerritClient;
    this.localizer = localizer;
    this.aiReviewHistoryCollector = new GerritAiReviewHistoryCollector();
    this.aiHistoryMessageFilter = new AiHistoryMessageFilter();
  }

  List<SpecializedReviewFindings.PastComment> collect(
      ChangeSetData changeSetData, GerritChange change) {
    if (changeSetData != null
        && Boolean.TRUE.equals(changeSetData.hasParsedCommand(CommandSet.FORGET_THREAD))) {
      return List.of();
    }
    if (gerritClient == null || localizer == null) {
      return List.of();
    }
    try {
      return aiReviewHistoryCollector
          .collect(
              config,
              localizer,
              changeSetData.getAiAccountId(),
              gerritClient.getClientData(change))
          .getEntries()
          .stream()
          .filter(aiHistoryMessageFilter::shouldIncludeReviewComment)
          .map(SpecializedReviewPastCommentsCollector::toPastComment)
          .filter(comment -> comment.getId() != null)
          .toList();
    } catch (Exception e) {
      log.debug("Unable to add structured past comments to historical repetition input", e);
      return List.of();
    }
  }

  private static SpecializedReviewFindings.PastComment toPastComment(
      AiReviewHistoryInfo.Entry entry) {
    return new SpecializedReviewFindings.PastComment(
        firstNonBlank(entry.getId(), entry.getChangeMessageId()),
        entry.getMessage(),
        entry.getFilename(),
        entry.getLine());
  }

  private static String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.isBlank()) {
      return primary;
    }
    return fallback == null || fallback.isBlank() ? null : fallback;
  }
}
