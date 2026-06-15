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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.patchset;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientPatchSetHelper.filterPatchWithoutCommitMessage;

@Slf4j
public class AiPromptReviewCode extends AiPromptReview implements IAiPrompt {

  public AiPromptReviewCode(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    loadDefaultPrompts("agents/level1/patchset/prompts");
    log.debug("AiPromptReviewCode initialized for project: {}", change.getProjectName());
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    addReviewInstructions(instructions);
    log.debug("Review Code specific AI Assistant Instructions added: {}", instructions);
  }

  @Override
  protected boolean includeCommitMessageReviewRequirement() {
    return false;
  }

  @Override
  public String getDefaultAiThreadReviewMessage(String patchSet) {
    String filteredPatchSet = filterPatchWithoutCommitMessage(change, patchSet);
    log.debug("Filtered Patch Set for Review Code: {}", filteredPatchSet);
    return super.getDefaultAiThreadReviewMessage(filteredPatchSet);
  }
}
