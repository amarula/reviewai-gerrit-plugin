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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai.AiClientBase;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand.OnDemandCodeContextTools;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiToolCall;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponseInputItem;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class OpenAiResponseToolOutputHandler extends AiClientBase {
  private final OnDemandCodeContextTools onDemandCodeContextTools;

  private List<AiToolCall> aiToolCalls;

  public OpenAiResponseToolOutputHandler(
      Configuration config, GerritChange change, GitRepoFiles gitRepoFiles) {
    super(config);
    onDemandCodeContextTools = new OnDemandCodeContextTools(config, change, gitRepoFiles);
  }

  public List<OpenAiResponseInputItem> buildToolOutputs(List<AiToolCall> aiToolCalls) {
    this.aiToolCalls = aiToolCalls;
    List<OpenAiResponseInputItem> toolOutputs = new ArrayList<>();
    log.debug("OpenAI Tool Calls: {}", aiToolCalls);
    for (int i = 0; i < aiToolCalls.size(); i++) {
      String output = getOutput(i);
      if (output.isEmpty()) {
        continue;
      }
      toolOutputs.add(
          OpenAiResponseInputItem.builder()
              .type("function_call_output")
              .callId(aiToolCalls.get(i).getId())
              .output(output)
              .build());
    }
    log.debug("OpenAI Tool Outputs: {}", toolOutputs);
    return toolOutputs;
  }

  private String getOutput(int i) {
    AiToolCall.Function function = getFunction(aiToolCalls, i);
    if (function != null && OnDemandCodeContextTools.FUNCTION_NAMES.contains(function.getName())) {
      log.debug("OpenAI `{}` Response Content: {}", function.getName(), function.getArguments());
      return onDemandCodeContextTools.execute(function.getName(), function.getArguments());
    }
    return "";
  }
}
