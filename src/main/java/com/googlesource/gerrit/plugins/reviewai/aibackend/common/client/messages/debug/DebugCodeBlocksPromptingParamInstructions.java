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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.patchset.AiPromptReviewCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage.AiPromptReviewCommitMessage;

import java.util.List;

public class DebugCodeBlocksPromptingParamInstructions extends DebugCodeBlocksPromptingParamBase {
  private static final String TITLE_FULL_REVIEW = "INSTRUCTIONS FOR FULL REVIEW";
  private static final String TITLE_PATCH_SET_ONLY = "INSTRUCTIONS FOR PATCH SET ONLY";
  private static final String TITLE_COMMIT_MESSAGE_ONLY = "INSTRUCTIONS FOR COMMIT MESSAGE ONLY";
  private static final String TITLE_SUGGEST_FULL_REVIEW =
      "INSTRUCTIONS FOR SUGGEST FULL REVIEW";
  private static final String TITLE_SUGGEST_PATCH_SET_ONLY =
      "INSTRUCTIONS FOR SUGGEST PATCH SET ONLY";
  private static final String TITLE_SUGGEST_COMMIT_MESSAGE_ONLY =
      "INSTRUCTIONS FOR SUGGEST COMMIT MESSAGE ONLY";
  private static final List<ScopedPromptingParameter> SCOPED_PARAMETERS =
      List.of(
          new ScopedPromptingParameter(ReviewScope.FULL, TITLE_FULL_REVIEW),
          new ScopedPromptingParameter(ReviewScope.PATCHSET, TITLE_PATCH_SET_ONLY),
          new ScopedPromptingParameter(ReviewScope.COMMIT_MESSAGE, TITLE_COMMIT_MESSAGE_ONLY));
  private static final List<ScopedPromptingParameter> SUGGEST_SCOPED_PARAMETERS =
      List.of(
          new ScopedPromptingParameter(ReviewScope.FULL, TITLE_SUGGEST_FULL_REVIEW),
          new ScopedPromptingParameter(ReviewScope.PATCHSET, TITLE_SUGGEST_PATCH_SET_ONLY),
          new ScopedPromptingParameter(
              ReviewScope.COMMIT_MESSAGE, TITLE_SUGGEST_COMMIT_MESSAGE_ONLY));

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

  public String getDebugCodeBlock() {
    if (changeSetData.getSuggestMode()) {
      return getScopedDebugCodeBlock(reviewScope, SUGGEST_SCOPED_PARAMETERS);
    }
    return getScopedDebugCodeBlock(reviewScope, SCOPED_PARAMETERS);
  }

  @Override
  protected void populateAiPromptParameters() {
    if (changeSetData.getSuggestMode()) {
      promptingParameters.put(
          TITLE_SUGGEST_FULL_REVIEW, getSuggestInstructions(ReviewScope.FULL));
      promptingParameters.put(
          TITLE_SUGGEST_PATCH_SET_ONLY, getSuggestInstructions(ReviewScope.PATCHSET));
      promptingParameters.put(
          TITLE_SUGGEST_COMMIT_MESSAGE_ONLY, getSuggestInstructions(ReviewScope.COMMIT_MESSAGE));
      return;
    }
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

  private String getSuggestInstructions(ReviewScope reviewScope) {
    ChangeSetData scopedChangeSetData = changeSetData.copy();
    scopedChangeSetData.setReviewScope(reviewScope);
    return AiPromptFactory.getAiPrompt(
            config, scopedChangeSetData.copyForSuggestion(), change, codeContextPolicy)
        .getDefaultAiAssistantInstructions();
  }
}
