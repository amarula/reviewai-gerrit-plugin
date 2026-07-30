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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

@Slf4j
public class AiPromptSuggest extends AiPromptReview {

  public AiPromptSuggest(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    loadPromptMap("promptsAiSuggest");
    log.debug("AiPromptSuggest initialized for project: {}", change.getProjectName());
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    instructions.add(getDefaultAiAssistantInstructions());
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    return joinWithDoubleNewLine(
        List.of(
            buildSection(prompt("DEFAULT_AI_SUGGEST_SECTION_TITLE_ROLE"), prompt("DEFAULT_AI_SUGGEST_INSTRUCTIONS_ROLE")),
            buildSection(
                prompt("DEFAULT_AI_SUGGEST_SECTION_TITLE_TASK"),
                getSuggestionTaskInstructions()),
            buildSection(prompt("DEFAULT_AI_SUGGEST_SECTION_TITLE_RESPONSE_FORMAT"), prompt("DEFAULT_AI_SUGGEST_RESPONSE_FORMAT"))));
  }

  private String getSuggestionTaskInstructions() {
    ReviewScope scope = changeSetData.getReviewScope();
    if (scope == ReviewScope.PATCHSET) {
      return prompt("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_PATCHSET");
    }
    if (scope == ReviewScope.COMMIT_MESSAGE) {
      return prompt("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_COMMIT_MESSAGE");
    }
    if (new AiPromptParameters(config).isMultiAgentModeEnabled()) {
      return getSuggestionTaskInstructions(changeSetData.getReviewAssistantStage());
    }
    return joinWithDoubleNewLine(
        List.of(
            prompt("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_PATCHSET"),
            prompt("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_COMMIT_MESSAGE")));
  }

  private String getSuggestionTaskInstructions(ReviewAssistantStage stage) {
    return stage == ReviewAssistantStage.REVIEW_COMMIT_MESSAGE
        ? prompt("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_COMMIT_MESSAGE")
        : prompt("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_PATCHSET");
  }

  @Override
  public String getDefaultAiThreadReviewMessage(String patchSet) {
    return String.format(prompt("DEFAULT_AI_SUGGEST_MESSAGE"), patchSet);
  }
}
