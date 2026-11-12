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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinitions;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReviewAgentSkillsTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void pipelineSkillSectionsPreserveExistingAgentInstructions() throws Exception {
    var expected =
        JsonParser.parseString(readResource("__files/skills/reviewPipelineSections.json"))
            .getAsJsonObject();
    for (var resource : expected.entrySet()) {
      for (var entry : resource.getValue().getAsJsonObject().entrySet()) {
        assertEquals(
            resource.getKey() + ": " + entry.getKey(),
            entry.getValue().getAsString(),
            ReviewAgentSkills.section(resource.getKey(), entry.getKey()));
      }
    }
  }

  @Test
  public void sharedReplyFieldsPreserveDescriptionsAndOrder() throws Exception {
    var expected =
        JsonParser.parseString(readResource("__files/skills/shared/reply-fields.json"))
            .getAsJsonObject();
    var fields = new AiPrompt(mock(Configuration.class)).getRepliesAttributes();
    assertEquals(expected, new Gson().toJsonTree(fields));
    assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(fields.keySet()));
  }

  @Test
  public void specialistSkillsPreserveIdentityDescriptionAndInstructions() throws Exception {
    var expected =
        JsonParser.parseString(readResource("__files/skills/reviewSpecialists.json"))
            .getAsJsonArray();
    assertEquals(expected.size(), SpecializedReviewAgentDefinitions.load().size());
    for (var item : expected) {
      var entry = item.getAsJsonObject();
      var actual =
          SpecializedReviewAgentDefinitions.findByName(entry.get("name").getAsString())
              .orElseThrow();
      assertEquals(entry.get("name").getAsString(), actual.getName());
      assertEquals(entry.get("short_description").getAsString(), actual.getShortDescription());
      assertEquals(entry.get("instructions").getAsString().strip(), actual.getInstructions());
      assertTrue(actual.isValid());
    }
  }

  @Test
  public void loadsPipelineAndSpecialistSkillsFromJarWithoutDirectoryEntries() throws Exception {
    Path jar = temporaryFolder.newFile("review-skills.jar").toPath();
    List<String> names =
        List.of(
            "review-common",
            "review-chat",
            "review-suggestions",
            "code-review",
            "commit-message-review",
            "concern-review",
            "new-issue-finder",
            "review-feedback",
            "security",
            "review-verification",
            "review-routing",
            "review-follow-up",
            "review-triage",
            "specialist-boundaries",
            "review-consolidation",
            "review-historical-repetition",
            "review-conflict-resolution");
    try (var output = new JarOutputStream(Files.newOutputStream(jar))) {
      for (String name : names) {
        String resource = "skills/" + name + "/SKILL.md";
        output.putNextEntry(new JarEntry(resource));
        output.write(readResource(resource).getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
        for (var asset : ReviewAgentSkills.load(name).resources()) {
          String assetPath = "skills/" + name + "/" + asset.relativePath();
          output.putNextEntry(new JarEntry(assetPath));
          output.write(readResource(assetPath).getBytes(StandardCharsets.UTF_8));
          output.closeEntry();
        }
      }
    }
    try (var classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
      for (String name : names) {
        var expected = ReviewAgentSkills.load(name);
        var actual = ReviewAgentSkills.load(name, classLoader);
        assertEquals(expected.name(), actual.name());
        assertEquals(expected.description(), actual.description());
        assertEquals(expected.content(), actual.content());
        assertEquals(expected.resources().size(), actual.resources().size());
        for (var resource : expected.resources()) {
          assertEquals(
              resource.content(),
              actual.resources().stream()
                  .filter(item -> item.relativePath().equals(resource.relativePath()))
                  .findFirst()
                  .orElseThrow()
                  .content());
        }
      }
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

  @Test
  public void rejectsUnknownSectionsAndPathsOutsideTheSkillRoot() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewAgentSkills.section("concern-review", "Missing section"));
    assertThrows(IllegalArgumentException.class, () -> ReviewAgentSkills.load("../config/prompts"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewAgentSkills.resource("concern-review", "assets/missing.md"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewAgentSkills.resource("concern-review", "../specialized-reviewers.json"));
  }

  private String readResource(String name) throws Exception {
    try (InputStream resource = getClass().getClassLoader().getResourceAsStream(name)) {
      assertNotNull(name, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
