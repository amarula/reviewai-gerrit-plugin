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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class ReviewAgentSkillsTest {
  @Test
  public void sharedGuidelinesResolveThroughExistingPromptKeys() throws Exception {
    String expected;
    try (InputStream resource =
        getClass().getClassLoader().getResourceAsStream("__files/skills/reviewGuidelines.txt")) {
      assertNotNull(resource);
      expected = new String(resource.readAllBytes(), StandardCharsets.UTF_8).strip();
    }
    for (String prompts :
        List.of("agents/level0/single-agent/prompts", "agents/level1/patchset/prompts")) {
      assertEquals(
          expected,
          AiPrompt.getJsonPromptValues(prompts)
              .get("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_GUIDELINES"));
    }
  }

  @Test
  public void sectionsPreserveFencesAndNestedHeadings() throws Exception {
    assertEquals(
        readResource("__files/skills/sectionBody.md").strip(),
        ReviewAgentSkills.section("section-parsing", "Role"));
    assertEquals(
        readResource("__files/skills/sectionTask.md").strip(),
        ReviewAgentSkills.section("section-parsing", "Task"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewAgentSkills.section("section-parsing", "Fenced example"));
  }

  private String readResource(String name) throws Exception {
    try (InputStream resource = getClass().getClassLoader().getResourceAsStream(name)) {
      assertNotNull(name, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
