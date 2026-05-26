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

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptBase.DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NO_FILE_CONTEXT;

@Slf4j
public class CodeContextPolicyNone extends CodeContextPolicyBase implements ICodeContextPolicy {

  @VisibleForTesting
  @Inject
  public CodeContextPolicyNone(Configuration config) {
    super(config);
    log.debug("CodeContextPolicyNone initialized");
  }

  @Override
  public void addCodeContextPolicyAwareAssistantInstructions(List<String> instructions) {
    instructions.add(DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NO_FILE_CONTEXT);
    log.debug("Added Assistant Instructions for `None` code context policy");
  }
}
