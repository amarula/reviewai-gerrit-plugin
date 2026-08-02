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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;

public class AiPromptSpecializedVerification extends AiPromptSpecializedReviewCollector {

  public AiPromptSpecializedVerification(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(
        config,
        changeSetData,
        change,
        codeContextPolicy,
        "agents/level2/stages/verification/prompts");
  }

  @Override
  protected String getCollectorRole() {
    return prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_VERIFICATION");
  }

  @Override
  protected String getCollectorRules() {
    return prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_VERIFICATION_RULES");
  }

  @Override
  protected String getCollectorResponseFormat() {
    return prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_VERIFICATION_RESPONSE_FORMAT")
        + "\n\n"
        + prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES_LOCATION");
  }

  @Override
  protected String getCollectorMessage() {
    return prompt("DEFAULT_AI_MESSAGE_SPECIALIZED_VERIFICATION");
  }
}
