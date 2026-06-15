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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt;

public class AiPromptReviewAgentRouter extends AiPrompt {
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_AGENT_ROUTER;
  public static String DEFAULT_AI_MESSAGE_REVIEW_AGENT_ROUTER;

  public AiPromptReviewAgentRouter(Configuration config) {
    super(config);
    loadDefaultPrompts("agents/level1/router/prompts");
  }

  public String getDefaultAiAssistantInstructions() {
    return DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_AGENT_ROUTER;
  }

  public String getDefaultAiThreadReviewMessage(String requestData) {
    return String.format(
        DEFAULT_AI_MESSAGE_REVIEW_AGENT_ROUTER, requestData == null ? "" : requestData);
  }
}
