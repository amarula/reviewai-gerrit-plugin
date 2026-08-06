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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiDataPrompt;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.patchset.AiPromptReviewCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage.AiPromptReviewCommitMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.AiPromptReviewReiterated;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router.AiPromptRoutedReviewAgentRequest;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewAgent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedConflictResolution;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedConsolidation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedHistoricalRepetition;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedVerification;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewTriage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.concerns.AiPromptConcernReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.concerns.AiPromptNewIssueFinder;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AiPromptFactory {

  public static IAiPrompt getAiPrompt(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    if (change.getIsCommentEvent() && !changeSetData.getForcedReview()) {
      if (changeSetData.getForcedStagedReview()) {
        log.debug("AiPromptFactory: Return AiPromptRoutedReviewAgentRequest");
        return new AiPromptRoutedReviewAgentRequest(
            config, changeSetData, change, codeContextPolicy);
      }
      log.debug("AiPromptFactory: Return AiPromptRequests");
      return new AiPromptRequests(config, changeSetData, change, codeContextPolicy);
    } else {
      if (changeSetData.getSuggestMode()) {
        log.debug("AiPromptFactory: Return AiPromptSuggest");
        return new AiPromptSuggest(config, changeSetData, change, codeContextPolicy);
      }
      AiPromptParameters aiPromptParameters = new AiPromptParameters(config);
      if (aiPromptParameters.isMultiAgentModeEnabled() || changeSetData.getForcedStagedReview()) {
        return switch (changeSetData.getReviewAssistantStage()) {
          case REVIEW_CODE -> {
            log.debug("AiPromptFactory: Return AiPromptReviewCode");
            yield new AiPromptReviewCode(config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_COMMIT_MESSAGE -> {
            if (changeSetData.getSpecializedAgentReview()) {
              log.debug("AiPromptFactory: Return AiPromptSpecializedReviewAgent");
              yield new AiPromptSpecializedReviewAgent(
                  config, changeSetData, change, codeContextPolicy);
            }
            log.debug("AiPromptFactory: Return AiPromptReviewCommitMessage");
            yield new AiPromptReviewCommitMessage(
                config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_CONCERNS -> {
            log.debug("AiPromptFactory: Return AiPromptConcernReview");
            yield new AiPromptConcernReview(config, changeSetData, change, codeContextPolicy);
          }
          case FIND_NEW_ISSUES -> {
            log.debug("AiPromptFactory: Return AiPromptNewIssueFinder");
            yield new AiPromptNewIssueFinder(config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_REITERATED -> {
            log.debug("AiPromptFactory: Return AiPromptReviewReiterate");
            yield new AiPromptReviewReiterated(config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_SPECIALIZED_TRIAGE -> {
            log.debug("AiPromptFactory: Return AiPromptSpecializedReviewTriage");
            yield new AiPromptSpecializedReviewTriage(
                config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_SPECIALIZED_AGENT -> {
            log.debug("AiPromptFactory: Return AiPromptSpecializedReviewAgent");
            yield new AiPromptSpecializedReviewAgent(
                config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_SPECIALIZED_CONSOLIDATION -> {
            log.debug("AiPromptFactory: Return AiPromptSpecializedConsolidation");
            yield new AiPromptSpecializedConsolidation(
                config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_SPECIALIZED_HISTORICAL_REPETITION -> {
            log.debug("AiPromptFactory: Return AiPromptSpecializedHistoricalRepetition");
            yield new AiPromptSpecializedHistoricalRepetition(
                config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_SPECIALIZED_CONFLICT_RESOLUTION -> {
            log.debug("AiPromptFactory: Return AiPromptSpecializedConflictResolution");
            yield new AiPromptSpecializedConflictResolution(
                config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_SPECIALIZED_VERIFICATION -> {
            log.debug("AiPromptFactory: Return AiPromptSpecializedVerification");
            yield new AiPromptSpecializedVerification(
                config, changeSetData, change, codeContextPolicy);
          }
        };
      } else {
        log.debug("AiPromptFactory: Return AiPromptReview for Unified Review");
        return new AiPromptReview(config, changeSetData, change, codeContextPolicy);
      }
    }
  }

  public static IAiDataPrompt getAiDataPrompt(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      GerritClientData gerritClientData,
      Localizer localizer) {
    if (change.getIsCommentEvent()) {
      log.debug("AiPromptFactory: Return ReferencedAiDataPromptRequests");
      return new ReferencedAiDataPromptRequests(config, changeSetData, gerritClientData, localizer);
    } else {
      log.debug("AiPromptFactory: Return AiDataPromptReview");
      return new AiDataPromptReview(config, changeSetData, gerritClientData, localizer);
    }
  }
}
