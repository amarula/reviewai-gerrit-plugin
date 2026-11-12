/*
 * Copyright (c) 2026. Amarula Solutions
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

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills.ReviewAgentSkills;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import java.util.ArrayList;
import java.util.List;

public class AiPromptSpecializedReviewTriage extends AiPromptReview {
  private static final String SKILL = "review-triage";

  public AiPromptSpecializedReviewTriage(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    this.defaultAiMessageReview = ReviewAgentSkills.resource(SKILL, "assets/request.md").strip();
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    List<String> sections = new ArrayList<>();
    sections.add(buildSection("Role", ReviewAgentSkills.section(SKILL, "Role")));
    sections.addAll(buildConditionLabelSections());
    sections.add(buildSection("Available Specialized Agents", getAvailableSpecializedAgents()));
    sections.add(buildSection("Scope and Review Constraints", getScopeAndReviewConstraints()));
    sections.add(buildSection("Mandatory Rules", getAiAssistantInstructionsReview()));
    sections.add(buildSection("MANDATORY Response Format", getTriageResponseFormat()));
    sections.add(
        buildSection(
            "Example response", ReviewAgentSkills.resource(SKILL, "assets/response-example.md")));
    return joinWithDoubleNewLine(sections);
  }

  private String getAvailableSpecializedAgents() {
    return ReviewAgentSkills.section(SKILL, "Commit Message Agent")
        + "\n"
        + SpecializedReviewAgentDefinitions.triageAgentList();
  }

  private String getTriageResponseFormat() {
    return String.format(
        ReviewAgentSkills.section(SKILL, "MANDATORY Response Format"),
        "COMMIT_MESSAGE, " + SpecializedReviewAgentDefinitions.agentNames());
  }
}
