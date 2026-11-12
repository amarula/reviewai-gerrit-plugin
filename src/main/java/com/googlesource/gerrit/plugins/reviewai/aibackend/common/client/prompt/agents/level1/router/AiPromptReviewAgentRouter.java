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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills.ReviewAgentSkills;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;

public class AiPromptReviewAgentRouter extends AiPrompt {
  private static final String SKILL = "review-routing";

  public AiPromptReviewAgentRouter(Configuration config) {
    super(config);
  }

  public String getDefaultAiAssistantInstructions() {
    return ReviewAgentSkills.section(SKILL, "Role");
  }

  public String getDefaultAiThreadReviewMessage(String requestData) {
    return String.format(
        ReviewAgentSkills.resource(SKILL, "assets/request.md").strip(),
        requestData == null ? "" : requestData);
  }
}
