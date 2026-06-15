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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.*;

@Slf4j
public class AiPromptReviewCommitMessage extends AiPromptReview implements IAiPrompt {
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES_GUIDELINES;

  public AiPromptReviewCommitMessage(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    loadDefaultPrompts("agents/level1/commit-message/prompts");
    this.defaultAiMessageReview = DEFAULT_AI_MESSAGE_REVIEW;
    log.debug(
        "Initialized AiPromptReviewCommitMessage for project: {}", change.getProjectName());
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    instructions.addAll(
        List.of(
            resolveCommitMessageInstructions(DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES),
            joinWithNewLine(
                new ArrayList<>(
                    List.of(
                        DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_RULES,
                        getAiAssistantInstructionsReview(false, true, false),
                        DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES))),
            DEFAULT_AI_REVIEW_PROMPT_INSTRUCTIONS_COMMIT_MESSAGES,
            getPatchSetReviewPromptInstructions(),
            DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES_GUIDELINES,
            DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_FORMAT,
            DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_EXAMPLES));
    log.debug("Commit Message Review specific AI Assistant Instructions added: {}", instructions);
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    List<String> sections = new ArrayList<>();
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE,
            resolveCommitMessageInstructions(DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES)
                + "\n\nReturn the feedback using this plugin's mandatory JSON response format, "
                + "not the standalone Gerrit UI Markdown code-block output format."));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_SCOPE_AND_REVIEW_CONSTRAINTS,
            getScopeAndReviewConstraints()));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RULES,
            getAiAssistantInstructionsReview(false, true, false)));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_COMMIT_MESSAGE_REVIEW_REQUIREMENT,
            getReviewPromptCommitMessages()));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_FIELD_DEFINITIONS,
            getPatchSetReviewPromptInstructions()));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_ADDITIONAL_REVIEW_GUIDELINES,
            DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES_GUIDELINES));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RESPONSE_FORMAT,
            getMandatoryResponseFormat()));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_EXAMPLE_RESPONSE,
            DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_EXAMPLES));

    String compiledInstructions = joinWithDoubleNewLine(sections);
    log.debug(
        "Compiled Commit Message Review specific AI Assistant Instructions: {}",
        compiledInstructions);
    return compiledInstructions;
  }
}
