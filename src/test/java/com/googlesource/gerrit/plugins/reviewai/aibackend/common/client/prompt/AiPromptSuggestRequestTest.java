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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import static org.junit.Assert.assertEquals;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import java.util.Map;
import org.junit.Test;

public class AiPromptSuggestRequestTest {
  private static final Map<String, Object> PROMPTS = AiPrompt.getJsonPromptValues("promptsAiSuggest");

  @Test
  public void reviewRepliesRequestUsesResourceTemplate() {
    assertEquals(
        String.format(prompt("DEFAULT_AI_SUGGEST_REVIEW_REPLIES_REQUEST"), "patch", "reviews"),
        AiPromptSuggestRequest.forReviewReplies("patch", "reviews"));
  }

  @Test
  public void existingContextRequestUsesResourceScopePrompt() {
    assertEquals(
        String.format(
            prompt("DEFAULT_AI_SUGGEST_EXISTING_CONTEXT_REQUEST"),
            "patch",
            prompt("DEFAULT_AI_SUGGEST_SCOPE_PATCHSET")),
        AiPromptSuggestRequest.forExistingContext("patch", ReviewScope.PATCHSET));
  }

  private static String prompt(String key) {
    return (String) PROMPTS.get(key);
  }
}
