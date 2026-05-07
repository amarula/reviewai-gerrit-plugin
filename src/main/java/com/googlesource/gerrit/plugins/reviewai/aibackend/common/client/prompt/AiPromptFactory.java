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
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.openai.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiParameters;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AiPromptFactory {

  public static IAiPrompt getAiPrompt(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    if (change.getIsCommentEvent()) {
      log.info("AiPromptFactory: Return AiPromptRequests");
      return new AiPromptRequests(config, changeSetData, change, codeContextPolicy);
    } else {
      OpenAiParameters openAiParameters = new OpenAiParameters(config, false);
      if (openAiParameters.isMultiAgentModeEnabled() || changeSetData.getForcedStagedReview()) {
        return switch (changeSetData.getReviewAssistantStage()) {
          case REVIEW_CODE -> {
            log.info("AiPromptFactory: Return AiPromptReviewCode");
            yield new AiPromptReviewCode(config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_COMMIT_MESSAGE -> {
            log.info("AiPromptFactory: Return AiPromptReviewCommitMessage");
            yield new AiPromptReviewCommitMessage(
                config, changeSetData, change, codeContextPolicy);
          }
          case REVIEW_REITERATED -> {
            log.info("AiPromptFactory: Return AiPromptReviewReiterate");
            yield new AiPromptReviewReiterated(
                config, changeSetData, change, codeContextPolicy);
          }
        };
      } else {
        log.info("AiPromptFactory: Return AiPromptReview for Unified Review");
        return new AiPromptReview(config, changeSetData, change, codeContextPolicy);
      }
    }
  }

  public static IAiPrompt getAiPrompt(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy,
      ReviewAssistantStages reviewAssistantStage) {
    changeSetData.setReviewAssistantStage(reviewAssistantStage);
    return getAiPrompt(config, changeSetData, change, codeContextPolicy);
  }

  public static IAiDataPrompt getAiDataPrompt(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      GerritClientData gerritClientData,
      Localizer localizer) {
    if (change.getIsCommentEvent()) {
      log.info("AiPromptFactory: Return OpenAiDataPromptRequests");
      return new OpenAiDataPromptRequests(config, changeSetData, gerritClientData, localizer);
    } else {
      log.info("AiPromptFactory: Return AiDataPromptReview");
      return new AiDataPromptReview(config, changeSetData, gerritClientData, localizer);
    }
  }
}
