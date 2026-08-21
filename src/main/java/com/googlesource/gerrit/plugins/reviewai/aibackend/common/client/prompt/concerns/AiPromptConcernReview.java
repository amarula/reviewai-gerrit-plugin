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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.concerns;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptSections;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernWorkflowInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import java.util.ArrayList;
import java.util.List;

public final class AiPromptConcernReview extends AiPromptBase {
  private static final String COMMIT_MESSAGE_REVIEWER = "COMMIT_MESSAGE";
  private static final String COMMIT_MESSAGE_ROLE_INSTRUCTIONS =
      (String)
          AiPrompt.getJsonPromptValues("agents/level1/commit-message/prompts")
              .get("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES");

  public AiPromptConcernReview(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    loadPromptMap("agents/common/concern-review/prompts");
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    instructions.add(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_CONCERN_REVIEW_ROLE"));
    instructions.add(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_CONCERN_REVIEW"));
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    List<String> instructions = new ArrayList<>();
    String specializedRole = specializedRoleInstructions();
    instructions.add(
        AiPromptSections.buildSection(
            prompt("DEFAULT_AI_CONCERN_REVIEW_SECTION_TITLE_ROLE"),
            specializedRole == null
                ? prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_CONCERN_REVIEW_ROLE")
                : specializedRole));
    if (!isCommitMessageReviewer()) {
      instructions.addAll(buildConditionLabelSections());
    }
    instructions.add(
        AiPromptSections.buildSection(
            prompt("DEFAULT_AI_CONCERN_REVIEW_SECTION_TITLE_TASK"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_CONCERN_REVIEW")));
    instructions.add(
        AiPromptSections.buildSection(
            prompt("DEFAULT_AI_CONCERN_REVIEW_SECTION_TITLE_RULES"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_CONCERN_REVIEW_RULES")));
    instructions.add(
        AiPromptSections.buildSection(
            prompt("DEFAULT_AI_CONCERN_REVIEW_SECTION_TITLE_RESPONSE_FORMAT"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_CONCERN_REVIEW_RESPONSE_FORMAT")));
    return joinWithDoubleNewLine(instructions);
  }

  @Override
  public String getDefaultAiThreadReviewMessage(String patchSet) {
    return String.format(
        prompt("DEFAULT_AI_MESSAGE_CONCERN_REVIEW"),
        getGson().toJson(changeSetData.getConcernWorkflowInput()));
  }

  @Override
  public String getAiRequestDataPrompt() {
    return null;
  }

  private String specializedRoleInstructions() {
    ConcernReviewerId reviewer = reviewer();
    String instructions = changeSetData.getSpecializedAgentInstructions();
    if (reviewer == null
        || reviewer.getKind() != ConcernReviewerId.Kind.SPECIALIZED_AGENT) {
      return null;
    }
    if (COMMIT_MESSAGE_REVIEWER.equals(reviewer.getName())) {
      return COMMIT_MESSAGE_ROLE_INSTRUCTIONS;
    }
    if (instructions == null || instructions.isBlank()) {
      return null;
    }
    return instructions;
  }

  private boolean isCommitMessageReviewer() {
    ConcernReviewerId reviewer = reviewer();
    return reviewer != null && COMMIT_MESSAGE_REVIEWER.equals(reviewer.getName());
  }

  private ConcernReviewerId reviewer() {
    ConcernWorkflowInput workflowInput = changeSetData.getConcernWorkflowInput();
    ReviewerConcerns concerns = workflowInput == null ? null : workflowInput.getConcerns();
    return concerns == null ? null : concerns.getReviewer();
  }
}
