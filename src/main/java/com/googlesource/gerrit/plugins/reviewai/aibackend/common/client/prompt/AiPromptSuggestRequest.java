/*
 * Copyright (c) 2026. Amarula Solutions
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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills.ReviewAgentSkills;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;

public final class AiPromptSuggestRequest {
  private static final String SKILL = "review-suggestions";

  private AiPromptSuggestRequest() {}

  public static String forReviewReplies(String patchSet, String reviewReplies) {
    return String.format(
        ReviewAgentSkills.resource(SKILL, "assets/review-replies-request.md").strip(),
        patchSet,
        reviewReplies);
  }

  public static String forExistingContext(String patchSet, ReviewScope reviewScope) {
    return String.format(
        ReviewAgentSkills.resource(SKILL, "assets/existing-context-request.md").strip(),
        patchSet,
        scopeInstruction(reviewScope));
  }

  private static String scopeInstruction(ReviewScope reviewScope) {
    if (reviewScope == ReviewScope.PATCHSET) {
      return " " + ReviewAgentSkills.section(SKILL, "Patchset Scope");
    }
    if (reviewScope == ReviewScope.COMMIT_MESSAGE) {
      return " " + ReviewAgentSkills.section(SKILL, "Commit Message Scope");
    }
    return "";
  }
}
