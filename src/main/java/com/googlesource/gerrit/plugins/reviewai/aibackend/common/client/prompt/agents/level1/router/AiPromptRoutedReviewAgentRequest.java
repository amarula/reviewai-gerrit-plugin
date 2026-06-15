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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptRequests;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AiPromptRoutedReviewAgentRequest extends AiPromptRequests {
  public AiPromptRoutedReviewAgentRequest(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    loadDefaultPrompts(AiPromptReview.class, "agents/level1/router/routed-request-prompts");
    log.debug(
        "AiPromptRoutedReviewAgentRequest initialized for stage {} and change ID {}",
        changeSetData.getReviewAssistantStage(),
        change.getFullChangeId());
  }

  @Override
  protected String getDefaultAiSystemPromptInstructions() {
    return AiPromptReview.getRoutedReviewAgentInstructions(changeSetData.getReviewAssistantStage());
  }
}
