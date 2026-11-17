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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.AiPromptReviewReiterated;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage.AiPromptReviewCommitMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.patchset.AiPromptReviewCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewAgent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewTriage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class ProjectInstructionsAppenderTest {
  private static final String SKILL_PATH = ".gerrit/skills/code-review/SKILL.md";
  private final GitRepoFiles files = mock(GitRepoFiles.class);
  private final GerritChange change = mock(GerritChange.class);
  private final ProjectInstructionsAppender appender = new ProjectInstructionsAppender(files);
  private IAiPrompt prompt;
  private String bundledInstructions;

  @Before
  public void setUp() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getAgentSpecializationLevel()).thenReturn(AgentSpecializationLevel.SINGLE_AGENT);
    when(config.getConfiguredAiSystemPromptInstructions())
        .thenReturn(Optional.of(readResource("__files/langchain/chatMemoryUserMessage.txt")));
    prompt =
        AiPromptFactory.getAiPrompt(
            config, new ChangeSetData(1), change, mock(ICodeContextPolicy.class));
    assertEquals(AiPromptReview.class, prompt.getClass());
    bundledInstructions = prompt.getDefaultAiAssistantInstructions();
  }

  @Test
  public void appendsOnlySkillBodyAndPreservesBundledInstructionsAndResources() throws Exception {
    when(files.getFileContent(change, SKILL_PATH)).thenReturn(projectResource("code-review.md"));

    String actual = appender.append(prompt, change, bundledInstructions);

    assertEquals(
        bundledInstructions
            + "\n\n# Project code-review guidance\n\n"
            + projectResource("guidance.md").strip(),
        actual);
    assertFalse(actual.contains("description:"));
    assertEquals(bundledInstructions, prompt.getDefaultAiAssistantInstructions());
    verify(files).getFileContent(change, SKILL_PATH);
    verifyNoMoreInteractions(files);
  }

  @Test
  public void missingSkillPreservesExistingPrompt() throws Exception {
    when(files.getFileContent(change, SKILL_PATH)).thenThrow(new FileNotFoundException(SKILL_PATH));

    assertSame(bundledInstructions, appender.append(prompt, change, bundledInstructions));
  }

  @Test
  public void invalidSkillsPreserveExistingPrompt() throws Exception {
    var invalid = JsonParser.parseString(projectResource("invalid-skills.json")).getAsJsonObject();
    for (var entry : invalid.entrySet()) {
      when(files.getFileContent(change, SKILL_PATH)).thenReturn(entry.getValue().getAsString());

      assertSame(
          entry.getKey(),
          bundledInstructions,
          appender.append(prompt, change, bundledInstructions));
    }
  }

  @Test
  public void acceptsWindowsLineEndingsAndPreservesMarkdownBody() throws Exception {
    when(files.getFileContent(change, SKILL_PATH))
        .thenReturn(projectResource("code-review.md").replace("\n", "\r\n"));

    assertTrue(
        appender
            .append(prompt, change, bundledInstructions)
            .endsWith(projectResource("guidance.md").replace("\n", "\r\n").strip()));
  }

  @Test
  public void projectGuidanceDoesNotLeakBetweenChanges() throws Exception {
    GerritChange otherChange = mock(GerritChange.class);
    when(files.getFileContent(change, SKILL_PATH)).thenReturn(projectResource("code-review.md"));
    when(files.getFileContent(otherChange, SKILL_PATH))
        .thenReturn(projectResource("alternate-code-review.md"));

    String first = appender.append(prompt, change, bundledInstructions);
    String second = appender.append(prompt, otherChange, bundledInstructions);

    assertTrue(first.endsWith(projectResource("guidance.md").strip()));
    assertTrue(second.endsWith(projectResource("alternate-guidance.md").strip()));
    assertFalse(second.contains(projectResource("guidance.md").strip()));
    assertFalse(first.contains(projectResource("alternate-guidance.md").strip()));
  }

  @Test
  public void readsUpdatedAndDeletedGuidanceForEachRequest() throws Exception {
    when(files.getFileContent(change, SKILL_PATH))
        .thenReturn(projectResource("code-review.md"), projectResource("alternate-code-review.md"))
        .thenThrow(new FileNotFoundException(SKILL_PATH));

    assertTrue(
        appender
            .append(prompt, change, bundledInstructions)
            .endsWith(projectResource("guidance.md").strip()));
    assertTrue(
        appender
            .append(prompt, change, bundledInstructions)
            .endsWith(projectResource("alternate-guidance.md").strip()));
    assertSame(bundledInstructions, appender.append(prompt, change, bundledInstructions));
  }

  @Test
  public void otherReviewStagesDoNotLoadCodeReviewGuidance() {
    for (IAiPrompt other :
        List.of(
            mock(AiPromptSuggest.class),
            mock(AiPromptReviewCode.class),
            mock(AiPromptReviewCommitMessage.class),
            mock(AiPromptReviewReiterated.class),
            mock(AiPromptSpecializedReviewAgent.class),
            mock(AiPromptSpecializedReviewTriage.class))) {
      assertSame(bundledInstructions, appender.append(other, change, bundledInstructions));
    }
    verifyNoInteractions(files);
  }

  @Test
  public void commentRequestsRetainGeneralProjectInstructions() throws Exception {
    String general = projectResource("guidance.md").strip();
    when(files.getFileContent(change, ".gerrit/ai-instructions.md")).thenReturn(general);

    assertEquals(
        bundledInstructions + " " + general,
        appender.append(mock(AiPromptRequests.class), change, bundledInstructions));
    verify(files).getFileContent(change, ".gerrit/ai-instructions.md");
    verifyNoMoreInteractions(files);
  }

  @Test
  public void unavailableRepositoryOrPromptPreservesExistingBehavior() {
    assertSame(
        bundledInstructions,
        new ProjectInstructionsAppender(null).append(prompt, change, bundledInstructions));
    assertNull(appender.append(prompt, change, null));
    assertSame(bundledInstructions, appender.append(null, change, bundledInstructions));
    verifyNoInteractions(files);
  }

  private String projectResource(String name) throws Exception {
    return readResource("__files/skills/project/" + name);
  }

  private String readResource(String name) throws Exception {
    try (InputStream resource = getClass().getClassLoader().getResourceAsStream(name)) {
      assertNotNull(name, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
