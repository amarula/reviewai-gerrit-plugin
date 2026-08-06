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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage.AiPromptReviewCommitMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.patchset.AiPromptReviewCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewAgent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.util.List;

public final class AiPromptNewIssueFinder extends AiPromptBase {
  private static final String COMMIT_MESSAGE_REVIEWER = "COMMIT_MESSAGE";

  private final IAiPrompt reviewPrompt;

  public AiPromptNewIssueFinder(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    loadPromptMap("agents/common/new-issue-finder/prompts");
    reviewPrompt = reviewPrompt(config, changeSetData, change, codeContextPolicy);
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    instructions.add(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NEW_ISSUE_FINDER"));
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    return joinWithDoubleNewLine(
        List.of(
            reviewPrompt.getDefaultAiAssistantInstructions(),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NEW_ISSUE_FINDER"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NEW_ISSUE_FINDER_RULES")));
  }

  @Override
  public String getDefaultAiThreadReviewMessage(String patchSet) {
    return String.format(
        prompt("DEFAULT_AI_MESSAGE_NEW_ISSUE_FINDER"),
        getGson().toJson(changeSetData.getConcernWorkflowInput()));
  }

  @Override
  public String getAiRequestDataPrompt() {
    return null;
  }

  private IAiPrompt reviewPrompt(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    ReviewerConcerns concerns = changeSetData.getConcernWorkflowInput().getConcerns();
    ConcernReviewerId reviewer = concerns == null ? null : concerns.getReviewer();
    if (reviewer == null || reviewer.getKind() == null) {
      return new AiPromptReview(config, changeSetData, change, codeContextPolicy);
    }
    return switch (reviewer.getKind()) {
      case SINGLE_AGENT -> new AiPromptReview(config, changeSetData, change, codeContextPolicy);
      case SCOPED_AGENT ->
          COMMIT_MESSAGE_REVIEWER.equals(reviewer.getName())
              ? new AiPromptReviewCommitMessage(config, changeSetData, change, codeContextPolicy)
              : new AiPromptReviewCode(config, changeSetData, change, codeContextPolicy);
      case SPECIALIZED_AGENT ->
          new AiPromptSpecializedReviewAgent(config, changeSetData, change, codeContextPolicy);
    };
  }
}
