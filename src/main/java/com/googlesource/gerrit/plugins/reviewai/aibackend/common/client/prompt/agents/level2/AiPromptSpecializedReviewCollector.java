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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import java.util.ArrayList;
import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

public abstract class AiPromptSpecializedReviewCollector extends AiPromptReview {
  protected AiPromptSpecializedReviewCollector(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy,
      String promptResource) {
    super(config, changeSetData, change, codeContextPolicy);
    loadPromptMap(promptResource);
    this.defaultAiMessageReview = getCollectorMessage();
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    List<String> sections = new ArrayList<>();
    sections.add(buildSection(prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE"), getCollectorRole()));
    sections.addAll(buildConditionLabelSections());
    sections.add(buildSection(
        prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RULES"), getCollectorRules()));
    sections.add(buildSection(
        prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RESPONSE_FORMAT"),
        getCollectorResponseFormat()));
    return joinWithDoubleNewLine(sections);
  }

  protected abstract String getCollectorRole();

  protected abstract String getCollectorRules();

  protected abstract String getCollectorResponseFormat();

  protected abstract String getCollectorMessage();
}
