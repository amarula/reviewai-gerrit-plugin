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

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview.DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ON_DEMAND_REQUEST;

@Slf4j
public class CodeContextPolicyOnDemand extends CodeContextPolicyBase implements ICodeContextPolicy {

  @VisibleForTesting
  @Inject
  public CodeContextPolicyOnDemand(
      Configuration config) {
    super(config);
    log.debug("CodeContextPolicyOnDemand initialized");
  }

  @Override
  public void addCodeContextPolicyAwareAssistantRule(List<String> rules) {
    rules.add(DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ON_DEMAND_REQUEST);
    log.debug("Added Assistant Rules for On-Demand code context policy");
  }
}
