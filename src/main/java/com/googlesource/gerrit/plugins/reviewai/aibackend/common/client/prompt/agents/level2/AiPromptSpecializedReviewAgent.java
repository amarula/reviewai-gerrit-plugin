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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage.AiPromptReviewCommitMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills.ReviewAgentSkills;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import java.util.ArrayList;
import java.util.List;

public class AiPromptSpecializedReviewAgent extends AiPromptReviewCommitMessage {
  private static final String SKILL = "specialist-boundaries";

  public AiPromptSpecializedReviewAgent(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    if (changeSetData.getSpecializedAgentName() == null) {
      return getCommitMessageSpecialistInstructions();
    }

    List<String> sections = new ArrayList<>();
    sections.add(buildSection("Role", getSpecializationInstructions()));
    sections.add(buildStrictSpecialistBoundarySection());
    sections.addAll(buildConditionLabelSections());
    sections.addAll(buildReviewFeedbackSections());
    sections.add(buildSection("Scope and Review Constraints", getScopeAndReviewConstraints()));
    sections.add(buildSection("Mandatory Rules", getAiAssistantInstructionsReview()));
    String customInstructions = changeSetData.getSpecializedAgentCustomInstructions();
    if (customInstructions != null && !customInstructions.isBlank()) {
      sections.add(buildSection("Triage-Selected Instructions", customInstructions));
    }
    sections.add(buildSection("Field Definitions", getSpecializedReplyFieldDefinitions(true)));
    sections.add(
        buildSection(
            "MANDATORY Response Format",
            String.format(
                ReviewAgentSkills.section(SKILL, "Patchset Response Format"),
                expectedOwnerAgent())));
    sections.add(
        buildSection(
            "Example response",
            String.format(
                ReviewAgentSkills.resource(SKILL, "assets/response-example.md"),
                expectedOwnerAgent())));
    return joinWithDoubleNewLine(sections);
  }

  private String getCommitMessageSpecialistInstructions() {
    List<String> sections = new ArrayList<>();
    sections.add(
        buildSection("Role", resolveCommitMessageInstructions(commitMessageSection("Role"))));
    sections.add(buildStrictSpecialistBoundarySection());
    sections.addAll(buildReviewFeedbackSections());
    sections.add(buildSection("Scope and Review Constraints", getScopeAndReviewConstraints()));
    sections.add(
        buildSection("Mandatory Rules", getAiAssistantInstructionsReview(false, true, false)));
    String customInstructions = changeSetData.getSpecializedAgentCustomInstructions();
    if (customInstructions != null && !customInstructions.isBlank()) {
      sections.add(buildSection("Triage-Selected Instructions", customInstructions));
    }
    sections.add(
        buildSection("Commit Message Review Requirement", getReviewPromptCommitMessages()));
    sections.add(buildSection("Field Definitions", getSpecializedReplyFieldDefinitions(false)));
    sections.add(buildSection("Additional Review Guidelines", commitMessageSection("Guidelines")));
    sections.add(
        buildSection(
            "MANDATORY Response Format",
            String.format(
                ReviewAgentSkills.section(SKILL, "Commit Message Response Format"),
                expectedOwnerAgent())));
    sections.add(
        buildSection(
            "Example response",
            String.format(
                ReviewAgentSkills.resource(SKILL, "assets/response-example.md"),
                expectedOwnerAgent())));
    return joinWithDoubleNewLine(sections);
  }

  private String getSpecializationInstructions() {
    return changeSetData.getSpecializedAgentInstructions();
  }

  private String buildStrictSpecialistBoundarySection() {
    return buildSection(
        "Strict Specialist Boundary",
        String.format(ReviewAgentSkills.section(SKILL, "Scope boundary"), expectedOwnerAgent()));
  }

  private String expectedOwnerAgent() {
    return changeSetData.getSpecializedAgentName() == null
        ? "COMMIT_MESSAGE"
        : SpecializedReviewAgentDefinition.normalizeName(changeSetData.getSpecializedAgentName());
  }

  private String getSpecializedReplyFieldDefinitions(boolean includeInlineLocationFields) {
    return String.format(
        ReviewAgentSkills.resource(SKILL, "assets/field-definitions.md").strip(),
        expectedOwnerAgent(),
        ReviewAgentSkills.section(
            SKILL, includeInlineLocationFields ? "Patchset Locations" : "Commit Message Locations"),
        getCommitMessageLocationInstructions());
  }

  @Override
  public String getDefaultAiThreadReviewMessage(String patchSet) {
    if (changeSetData.getSpecializedAgentName() == null) {
      return super.getDefaultAiThreadReviewMessage(patchSet);
    }
    return String.format(ReviewAgentSkills.resource(SKILL, "assets/request.md").strip(), patchSet);
  }
}
