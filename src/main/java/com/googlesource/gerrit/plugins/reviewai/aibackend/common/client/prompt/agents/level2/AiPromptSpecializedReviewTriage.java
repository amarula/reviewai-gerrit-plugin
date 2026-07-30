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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

public class AiPromptSpecializedReviewTriage extends AiPromptReview {

  public AiPromptSpecializedReviewTriage(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    loadPromptMap("agents/level2/triage/prompts");
    this.defaultAiMessageReview = prompt("DEFAULT_AI_MESSAGE_SPECIALIZED_TRIAGE");
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    return joinWithDoubleNewLine(
        List.of(
            buildSection(prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE"), prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_TRIAGE")),
            buildSection("Available Specialized Agents", getAvailableSpecializedAgents()),
            buildSection(prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_SCOPE_AND_REVIEW_CONSTRAINTS"), getScopeAndReviewConstraints()),
            buildSection(prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RULES"), getAiAssistantInstructionsReview()),
            buildSection(
                prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RESPONSE_FORMAT"),
                getTriageResponseFormat()),
            buildSection(prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_EXAMPLE_RESPONSE"), prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_TRIAGE_RESPONSE_EXAMPLE"))));
  }

  private String getAvailableSpecializedAgents() {
    return "COMMIT_MESSAGE: Review the commit subject, body, intent, clarity, and consistency with the patch.\n"
        + SpecializedReviewAgentDefinitions.triageAgentList();
  }

  private String getTriageResponseFormat() {
    return String.format(
        prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_TRIAGE_RESPONSE_FORMAT"),
        "COMMIT_MESSAGE, " + SpecializedReviewAgentDefinitions.agentNames());
  }
}
