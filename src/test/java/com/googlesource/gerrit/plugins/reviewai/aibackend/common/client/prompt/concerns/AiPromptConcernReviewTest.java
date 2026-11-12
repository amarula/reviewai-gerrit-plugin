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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.concerns;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernWorkflowInput;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class AiPromptConcernReviewTest {
  @Test
  public void preservesCompleteSystemPrompt() throws Exception {
    assertEquals(readFixture("system.md").strip(), prompt().getDefaultAiAssistantInstructions());
  }

  @Test
  public void preservesCompleteRequestWithWorkflowData() throws Exception {
    assertEquals(readFixture("request.md").strip(), prompt().getDefaultAiThreadReviewMessage(""));
  }

  private AiPromptConcernReview prompt() throws Exception {
    ChangeSetData data = new ChangeSetData(1);
    data.setConcernWorkflowInput(
        getGson().fromJson(readFixture("input.json"), ConcernWorkflowInput.class));
    return new AiPromptConcernReview(
        mock(Configuration.class), data, mock(GerritChange.class), mock(ICodeContextPolicy.class));
  }

  private String readFixture(String name) throws Exception {
    String path = "__files/skills/concern-review/" + name;
    try (var resource = getClass().getClassLoader().getResourceAsStream(path)) {
      assertNotNull(path, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
