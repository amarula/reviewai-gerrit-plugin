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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.code.context;

import com.google.common.annotations.VisibleForTesting;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyOnDemand;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiToolCall;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiResponseToolOutputHandler;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiTools;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiAssistantTools;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponseInputItem;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class OpenAiCodeContextPolicyOnDemand extends CodeContextPolicyOnDemand implements ICodeContextPolicy {
  private final GerritChange change;
  private final GitRepoFiles gitRepoFiles;

  @VisibleForTesting
  @Inject
  public OpenAiCodeContextPolicyOnDemand(
      Configuration config, GerritChange change, GitRepoFiles gitRepoFiles) {
    super(config);
    this.change = change;
    this.gitRepoFiles = gitRepoFiles;
    log.debug("OpenAiCodeContextPolicyOnDemand initialized");
  }

  @Override
  public List<OpenAiResponseInputItem> buildToolResponseItems(List<AiToolCall> aiToolCalls)
      throws AiConnectionFailException {
    log.debug("Building tool response items with On-Demand code context policy");
    return new OpenAiResponseToolOutputHandler(config, change, gitRepoFiles)
        .buildToolOutputs(aiToolCalls);
  }

  @Override
  public void updateOpenAiTools(OpenAiAssistantTools openAiAssistantTools) {
    openAiAssistantTools
        .getTools()
        .add(new OpenAiTools(OpenAiTools.Functions.tree).retrieveFunctionTool());
    openAiAssistantTools
        .getTools()
        .add(new OpenAiTools(OpenAiTools.Functions.getContent).retrieveFunctionTool());
    openAiAssistantTools
        .getTools()
        .add(new OpenAiTools(OpenAiTools.Functions.grep).retrieveFunctionTool());
    log.debug(
        "Updated Assistant Tools for On-Demand code context policy: {}", openAiAssistantTools);
  }
}
