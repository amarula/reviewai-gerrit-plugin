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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage.AiPromptReviewCommitMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import java.util.ArrayList;
import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

public class AiPromptSpecializedReviewAgent extends AiPromptReviewCommitMessage {

  public AiPromptSpecializedReviewAgent(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    loadPromptMap("agents/level2/specialized/prompts");
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    if (changeSetData.getSpecializedAgentName() == null) {
      return getCommitMessageSpecialistInstructions();
    }

    List<String> sections = new ArrayList<>();
    sections.add(buildSection(prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE"), getSpecializationInstructions()));
    sections.addAll(buildConditionLabelSections());
    sections.addAll(buildReviewFeedbackSections());
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_SCOPE_AND_REVIEW_CONSTRAINTS"),
            getScopeAndReviewConstraints()));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RULES"),
            getAiAssistantInstructionsReviewWithoutDirectives()));
    String customInstructions = changeSetData.getSpecializedAgentCustomInstructions();
    if (customInstructions != null && !customInstructions.isBlank()) {
      sections.add(buildSection("Triage-Selected Instructions", customInstructions));
    }
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_FIELD_DEFINITIONS"),
            getSpecializedReplyFieldDefinitions(true)));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RESPONSE_FORMAT"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_PATCHSET_RESPONSE_FORMAT")));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_EXAMPLE_RESPONSE"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_RESPONSE_EXAMPLES")));
    return joinWithDoubleNewLine(sections);
  }

  private String getCommitMessageSpecialistInstructions() {
    List<String> sections = new ArrayList<>();
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE"),
            resolveCommitMessageInstructions(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES"))));
    sections.addAll(buildReviewFeedbackSections());
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_SCOPE_AND_REVIEW_CONSTRAINTS"),
            getScopeAndReviewConstraints()));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RULES"),
            getAiAssistantInstructionsReviewWithoutDirectives(false, true, false)));
    String customInstructions = changeSetData.getSpecializedAgentCustomInstructions();
    if (customInstructions != null && !customInstructions.isBlank()) {
      sections.add(buildSection("Triage-Selected Instructions", customInstructions));
    }
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_COMMIT_MESSAGE_REVIEW_REQUIREMENT"),
            getReviewPromptCommitMessages()));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_FIELD_DEFINITIONS"),
            getSpecializedReplyFieldDefinitions(false)));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_ADDITIONAL_REVIEW_GUIDELINES"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES_GUIDELINES")));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RESPONSE_FORMAT"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_COMMIT_MESSAGE_RESPONSE_FORMAT")));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_EXAMPLE_RESPONSE"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_RESPONSE_EXAMPLES")));
    return joinWithDoubleNewLine(sections);
  }

  private String getSpecializationInstructions() {
    return changeSetData.getSpecializedAgentInstructions();
  }

  private String getSpecializedReplyFieldDefinitions(boolean includeInlineLocationFields) {
    String locations =
        includeInlineLocationFields
            ? "`locations`: array of precise objects with `filename`, `lineNumber`, and `codeSnippet`; "
            : "`locations`: array with the exact commit-message filename from the patch input; ";
    return "`concerns`: array of candidate issues that may deserve a final review comment; "
        + "`dismissed_concerns`: array of investigated candidate issues that do not apply; "
        + "`type`: concise category such as Correctness, Testability, Code Quality, Documentation, Security, or Commit Message; "
        + "`description`: precise statement of the candidate issue; "
        + "`reasoning`: evidence, triggering condition, and why the issue matters; "
        + "`preexisting`: true only when the concern existed before this patch; "
        + locations
        + prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES_LOCATION")
        + " "
        + "Specialized agents must not write final Gerrit comments and must not include `reply`, `score`, `relevance`, `duplicated`, `repeated`, `conflicting`, or `source_agent` fields.";
  }

  @Override
  public String getDefaultAiThreadReviewMessage(String patchSet) {
    if (changeSetData.getSpecializedAgentName() == null) {
      return super.getDefaultAiThreadReviewMessage(patchSet);
    }
    return String.format(prompt("DEFAULT_AI_MESSAGE_SPECIALIZED_REVIEW"), patchSet);
  }
}
