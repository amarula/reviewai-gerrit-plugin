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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.*;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills.ReviewAgentSkills;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AiPromptReviewCommitMessage extends AiPromptReview implements IAiPrompt {

  public AiPromptReviewCommitMessage(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    this.defaultAiMessageReview = getDefaultAiMessageReview();
    log.debug("Initialized AiPromptReviewCommitMessage for project: {}", change.getProjectName());
  }

  protected static String commitMessageSection(String title) {
    return ReviewAgentSkills.section("commit-message-review", title);
  }

  @Override
  public String getDefaultAiSystemPromptInstructions() {
    return commitMessageSection("System");
  }

  @Override
  public String getDefaultAiMessageReview() {
    return ReviewAgentSkills.resource("commit-message-review", "assets/request.md").strip();
  }

  @Override
  public String getDefaultAiAssistantInstructionsResponseExamples() {
    return ReviewAgentSkills.resource("commit-message-review", "assets/response-example.md");
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    instructions.addAll(
        List.of(
            resolveCommitMessageInstructions(commitMessageSection("Role")),
            joinWithNewLine(
                new ArrayList<>(
                    List.of(
                        reviewSection("Mandatory Rules"),
                        getAiAssistantInstructionsReview(false, true, false),
                        commitMessageSection("Role")))),
            getDefaultAiReviewPromptInstructionsCommitMessages(),
            getCommitMessageLocationInstructions(),
            getPatchSetReviewPromptInstructions(),
            commitMessageSection("Guidelines"),
            getDefaultAiAssistantInstructionsResponseFormat(),
            getDefaultAiAssistantInstructionsResponseExamples()));
    log.debug("Commit Message Review specific AI Assistant Instructions added: {}", instructions);
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    List<String> sections = new ArrayList<>();
    sections.add(
        buildSection(
            "Role",
            resolveCommitMessageInstructions(commitMessageSection("Role"))
                + "\n\n"
                + commitMessageSection("Output Format")));
    sections.addAll(buildReviewFeedbackSections());
    sections.add(buildSection("Scope and Review Constraints", getScopeAndReviewConstraints()));
    sections.add(
        buildSection("Mandatory Rules", getAiAssistantInstructionsReview(false, true, false)));
    sections.add(
        buildSection("Commit Message Review Requirement", getReviewPromptCommitMessages()));
    sections.add(
        buildSection(
            "Field Definitions",
            getPatchSetReviewPromptInstructions()
                + "\n\n"
                + getCommitMessageLocationInstructions()));
    sections.add(buildSection("Additional Review Guidelines", commitMessageSection("Guidelines")));
    sections.add(buildSection("MANDATORY Response Format", getMandatoryResponseFormat()));
    sections.add(
        buildSection("Example response", getDefaultAiAssistantInstructionsResponseExamples()));

    String compiledInstructions = joinWithDoubleNewLine(sections);
    log.debug(
        "Compiled Commit Message Review specific AI Assistant Instructions: {}",
        compiledInstructions);
    return compiledInstructions;
  }
}
