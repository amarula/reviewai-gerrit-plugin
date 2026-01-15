/*
 * Copyright (c) 2025. The Android Open Source Project
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

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.OperationNotSupportedException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.ClientBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.endpoint.OpenAiRun;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiAssistantTools;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiRunResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public abstract class CodeContextPolicyBase extends ClientBase implements ICodeContextPolicy {
  public enum CodeContextPolicies {
    NONE,
    ON_DEMAND,
    UPLOAD_ALL
  }

  public CodeContextPolicyBase(Configuration config) {
    super(config);
  }

  public void setupRunAction(OpenAiRun openAiRun) {
    log.debug("Setup Run Action skipped with the current code context policy");
  }

  public boolean runActionRequired(OpenAiRunResponse runResponse)
      throws AiConnectionFailException {
    log.debug("Run Action Required checking skipped with the current code context policy");
    return false;
  }

  public String generateVectorStore() throws AiConnectionFailException {
    log.debug("Vector Store generating skipped with the current code context policy");
    return null;
  }

  public void removeVectorStore() {
    log.debug("Vector Store removal skipped with the current code context policy");
  }

  public void updateAssistantTools(
      OpenAiAssistantTools openAiAssistantTools, String vectorStoreId) {
    log.debug("Assistant Tools updating skipped with the current code context policy");
  }

  public void addCodeContextPolicyAwareAssistantInstructions(List<String> instructions) {
    log.debug("Adding Assistant Instructions skipped with the current code context policy");
  }

  public void addCodeContextPolicyAwareAssistantRule(List<String> rules) {
    log.debug("Adding Assistant Rules skipped with the current code context policy");
  }
}
