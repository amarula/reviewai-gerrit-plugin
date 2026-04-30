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

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt.AiPromptReviewCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt.AiPromptReviewCommitMessage;

import java.util.ArrayList;
import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.CODE_DELIMITER;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.CODE_DELIMITER_BEGIN;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.distanceCodeDelimiter;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithNewLine;

public class DebugCodeBlocksPromptingParamInstructions extends DebugCodeBlocksPromptingParamBase {
  private static final String TITLE_FULL_REVIEW = "INSTRUCTIONS FOR FULL REVIEW";
  private static final String TITLE_PATCH_SET_ONLY = "INSTRUCTIONS FOR PATCH SET ONLY";
  private static final String TITLE_COMMIT_MESSAGE_ONLY = "INSTRUCTIONS FOR COMMIT MESSAGE ONLY";
  private final ReviewScope reviewScope;

  public DebugCodeBlocksPromptingParamInstructions(
      Localizer localizer,
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy,
      ReviewScope reviewScope) {
    super(
        localizer,
        "message.dump.instructions.title",
        config,
        changeSetData,
        change,
        codeContextPolicy);
    this.reviewScope = reviewScope;
  }

  @Override
  public String getDebugCodeBlock() {
    populateOpenAiParameters();
    List<String> sections = new ArrayList<>();
    if (shouldInclude(ReviewScope.FULL)) {
      sections.add(getSection(TITLE_FULL_REVIEW, promptingParameters.get(TITLE_FULL_REVIEW)));
    }
    if (shouldInclude(ReviewScope.PATCHSET)) {
      sections.add(getSection(TITLE_PATCH_SET_ONLY, promptingParameters.get(TITLE_PATCH_SET_ONLY)));
    }
    if (shouldInclude(ReviewScope.COMMIT_MESSAGE)) {
      sections.add(
          getSection(
              TITLE_COMMIT_MESSAGE_ONLY, promptingParameters.get(TITLE_COMMIT_MESSAGE_ONLY)));
    }
    return joinWithDoubleNewLine(sections);
  }

  @Override
  protected void populateOpenAiParameters() {
    promptingParameters.put(
        TITLE_FULL_REVIEW,
        new AiPromptReview(config, changeSetData.copy(), change, codeContextPolicy)
            .getDefaultAiAssistantInstructions());
    promptingParameters.put(
        TITLE_PATCH_SET_ONLY,
        new AiPromptReviewCode(config, changeSetData.copy(), change, codeContextPolicy)
            .getDefaultAiAssistantInstructions());
    promptingParameters.put(
        TITLE_COMMIT_MESSAGE_ONLY,
        new AiPromptReviewCommitMessage(config, changeSetData.copy(), change, codeContextPolicy)
            .getDefaultAiAssistantInstructions());
  }

  private String getSection(String title, String instructions) {
    return CODE_DELIMITER_BEGIN
        + joinWithNewLine(List.of(title, distanceCodeDelimiter(instructions)))
        + "\n"
        + CODE_DELIMITER;
  }

  private boolean shouldInclude(ReviewScope scope) {
    return reviewScope == null || reviewScope == scope;
  }

  @Override
  protected void populateOpenAISpecializedCodeReviewParameters() {}

  @Override
  protected void populateOpenAISpecializedCommitMessageReviewParameters() {}

  @Override
  protected void populateOpenAIReviewParameters() {}
}
