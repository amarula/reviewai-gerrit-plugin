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

package com.googlesource.gerrit.plugins.reviewai.data;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiDataPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPermittedVotingRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ChangeSetDataHandler {
  public static void update(
      Configuration config,
      GerritChange change,
      GerritClient gerritClient,
      ChangeSetData changeSetData,
      Localizer localizer) {
    GerritClientData gerritClientData = gerritClient.getClientData(change);
    AiDataPrompt aiDataPrompt =
        new AiDataPrompt(config, changeSetData, change, gerritClientData, localizer);

    changeSetData.setCommentPropertiesSize(gerritClientData.getCommentProperties().size());
    changeSetData.setAiDataPrompt(aiDataPrompt.buildPrompt());
    if (config.isVotingEnabled() && !change.getIsCommentEvent()) {
      GerritPermittedVotingRange permittedVotingRange =
          gerritClient.getPermittedVotingRange(change);
      if (permittedVotingRange != null) {
        if (permittedVotingRange.getMin() > config.getVotingMinScore()) {
          log.debug("Minimum AI voting score set to {}", permittedVotingRange.getMin());
          changeSetData.setVotingMinScore(permittedVotingRange.getMin());
        }
        if (permittedVotingRange.getMax() < config.getVotingMaxScore()) {
          log.debug("Maximum AI voting score set to {}", permittedVotingRange.getMax());
          changeSetData.setVotingMaxScore(permittedVotingRange.getMax());
        }
      }
    }
    log.debug("ChangeSetData updated: {}", changeSetData);
  }
}
