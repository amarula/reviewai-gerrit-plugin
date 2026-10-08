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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import java.util.Map;

public final class AiPromptSuggestRequest {
  private static final Map<String, Object> PROMPTS =
      AiPrompt.getJsonPromptValues("promptsAiSuggest");

  private AiPromptSuggestRequest() {}

  /**
   * Asks for fixes to the open concerns, each supplied with the concern it answers.
   *
   * <p>Distinct from {@link #forReviewReplies}: this one is given problems the reviewer has already
   * raised and kept, so it proposes fixes for those rather than for whatever a fresh review
   * happened to call negative.
   */
  public static String forOpenConcerns(String patchSet, String concerns) {
    return String.format(prompt("DEFAULT_AI_SUGGEST_OPEN_CONCERNS_REQUEST"), patchSet, concerns);
  }

  public static String forReviewReplies(String patchSet, String reviewReplies) {
    return String.format(
        prompt("DEFAULT_AI_SUGGEST_REVIEW_REPLIES_REQUEST"), patchSet, reviewReplies);
  }

  public static String forExistingContext(String patchSet, ReviewScope reviewScope) {
    return String.format(
        prompt("DEFAULT_AI_SUGGEST_EXISTING_CONTEXT_REQUEST"),
        patchSet,
        scopeInstruction(reviewScope));
  }

  private static String scopeInstruction(ReviewScope reviewScope) {
    if (reviewScope == ReviewScope.PATCHSET) {
      return prompt("DEFAULT_AI_SUGGEST_SCOPE_PATCHSET");
    }
    if (reviewScope == ReviewScope.COMMIT_MESSAGE) {
      return prompt("DEFAULT_AI_SUGGEST_SCOPE_COMMIT_MESSAGE");
    }
    return "";
  }

  private static String prompt(String key) {
    return (String) PROMPTS.get(key);
  }
}
