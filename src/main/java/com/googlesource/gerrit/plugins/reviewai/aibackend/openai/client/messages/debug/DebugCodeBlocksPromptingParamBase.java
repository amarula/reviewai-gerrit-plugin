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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.messages.debug;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksComposer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiParameters;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.openai.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory.getAiPrompt;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.distanceCodeDelimiter;

public abstract class DebugCodeBlocksPromptingParamBase extends DebugCodeBlocksComposer {
  private final Configuration config;
  private final ChangeSetData changeSetData;
  private final GerritChange change;
  private final ICodeContextPolicy codeContextPolicy;
  protected final LinkedHashMap<String, String> promptingParameters = new LinkedHashMap<>();

  protected IAiPrompt aIPrompt;

  public DebugCodeBlocksPromptingParamBase(
      Localizer localizer,
      String titleKey,
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(localizer, titleKey);
    this.config = config;
    this.change = change;
    this.changeSetData = changeSetData;
    this.codeContextPolicy = codeContextPolicy;
  }

  public String getDebugCodeBlock() {
    return super.getDebugCodeBlock(getPromptingParameters());
  }

  private List<String> getPromptingParameters() {
    switch (config.getAiBackend()) {
      case OPENAI -> populateOpenAiParameters();
    }
    return promptingParameters.entrySet().stream()
        .map(e -> getAsTitle(e.getKey()) + "\n" + distanceCodeDelimiter(e.getValue()) + "\n")
        .collect(Collectors.toList());
  }

  protected void populateOpenAiParameters() {
    OpenAiParameters openAiParameters = new OpenAiParameters(config, false);
    aIPrompt =
        getAiPrompt(
            config, changeSetData, change, codeContextPolicy, ReviewAssistantStages.REVIEW_CODE);
    if (openAiParameters.shouldSpecializeAssistants()) {
      populateOpenAISpecializedCodeReviewParameters();
      aIPrompt =
          getAiPrompt(
              config,
              changeSetData,
              change,
              codeContextPolicy,
              ReviewAssistantStages.REVIEW_COMMIT_MESSAGE);
      populateOpenAISpecializedCommitMessageReviewParameters();
    } else {
      populateOpenAIReviewParameters();
    }
  }

  protected abstract void populateOpenAISpecializedCodeReviewParameters();

  protected abstract void populateOpenAISpecializedCommitMessageReviewParameters();

  protected abstract void populateOpenAIReviewParameters();
}
