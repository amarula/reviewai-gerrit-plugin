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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiToolCall;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.ClientBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiAssistantTools;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponseInputItem;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;

@Slf4j
public abstract class CodeContextPolicyBase extends ClientBase implements ICodeContextPolicy {
  public enum CodeContextPolicies {
    NONE,
    ON_DEMAND
  }

  public CodeContextPolicyBase(Configuration config) {
    super(config);
  }

  public List<OpenAiResponseInputItem> buildToolResponseItems(List<AiToolCall> aiToolCalls)
      throws AiConnectionFailException {
    log.debug("Tool response building skipped with the current code context policy");
    return Collections.emptyList();
  }

  public void updateOpenAiTools(OpenAiAssistantTools openAiAssistantTools) {
    log.debug("AI tools updating skipped with the current code context policy");
  }

  public void addCodeContextPolicyAwareAssistantInstructions(List<String> instructions) {
    log.debug("Adding Assistant Instructions skipped with the current code context policy");
  }

  public void addCodeContextPolicyAwareAssistantRule(List<String> rules) {
    log.debug("Adding Assistant Rules skipped with the current code context policy");
  }
}
