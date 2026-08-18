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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.feedback;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import java.util.List;

public final class AiPromptReviewFeedbackClassification extends AiPromptBase {
  public AiPromptReviewFeedbackClassification(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    loadPromptMap("agents/common/review-feedback/prompts");
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    instructions.add(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_FEEDBACK"));
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    return joinWithDoubleNewLine(
        List.of(
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_FEEDBACK"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_FEEDBACK_CATEGORIES"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_FEEDBACK_MEMORY")));
  }

  @Override
  public String getDefaultAiThreadReviewMessage(String patchSet) {
    return String.format(
        prompt("DEFAULT_AI_MESSAGE_REVIEW_FEEDBACK"),
        getGson().toJson(changeSetData.getReviewFeedbackClassificationInput()));
  }

  @Override
  public String getAiRequestDataPrompt() {
    return null;
  }
}
