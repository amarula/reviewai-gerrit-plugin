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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;

public class AiPromptSpecializedDuplicationCollector extends AiPromptSpecializedReviewCollector {
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_DUPLICATION_COLLECTOR;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_DUPLICATION_COLLECTOR_RULES;
  public static String
      DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_DUPLICATION_COLLECTOR_RESPONSE_FORMAT;
  public static String DEFAULT_AI_MESSAGE_SPECIALIZED_DUPLICATION_COLLECTOR;

  public AiPromptSpecializedDuplicationCollector(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(
        config,
        changeSetData,
        change,
        codeContextPolicy,
        "agents/level2/collector/duplication/prompts");
  }

  @Override
  protected String getCollectorRole() {
    return DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_DUPLICATION_COLLECTOR;
  }

  @Override
  protected String getCollectorRules() {
    return DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_DUPLICATION_COLLECTOR_RULES;
  }

  @Override
  protected String getCollectorResponseFormat() {
    return DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_DUPLICATION_COLLECTOR_RESPONSE_FORMAT;
  }

  @Override
  protected String getCollectorMessage() {
    return DEFAULT_AI_MESSAGE_SPECIALIZED_DUPLICATION_COLLECTOR;
  }
}
