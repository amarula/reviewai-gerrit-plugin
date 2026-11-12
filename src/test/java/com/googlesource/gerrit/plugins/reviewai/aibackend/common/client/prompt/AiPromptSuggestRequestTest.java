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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class AiPromptSuggestRequestTest {
  @Test
  public void reviewRepliesRequestUsesResourceTemplate() throws Exception {
    assertEquals(
        expected("review_replies"), AiPromptSuggestRequest.forReviewReplies("patch", "reviews"));
  }

  @Test
  public void existingContextRequestUsesResourceScopePrompt() throws Exception {
    assertEquals(
        expected("patchset_context"),
        AiPromptSuggestRequest.forExistingContext("patch", ReviewScope.PATCHSET));
  }

  @Test
  public void existingContextRequestPreservesCommitMessageScope() throws Exception {
    assertEquals(
        expected("commit_message_context"),
        AiPromptSuggestRequest.forExistingContext("patch", ReviewScope.COMMIT_MESSAGE));
  }

  @Test
  public void existingContextRequestPreservesUnscopedRequests() throws Exception {
    assertEquals(
        expected("unscoped_context"), AiPromptSuggestRequest.forExistingContext("patch", null));
  }

  private String expected(String name) throws Exception {
    try (var resource =
        getClass()
            .getClassLoader()
            .getResourceAsStream("__files/skills/shared/suggest-requests.json")) {
      assertNotNull(resource);
      return getGson()
          .fromJson(new String(resource.readAllBytes(), StandardCharsets.UTF_8), JsonObject.class)
          .get(name)
          .getAsString();
    }
  }
}
