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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.AiPromptReviewReiterated;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage.AiPromptReviewCommitMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.patchset.AiPromptReviewCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewAgent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewTriage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinitions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills.ProjectSkillInstructions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;

public class ProjectInstructionsAppenderTest {
  private static final String SKILL_PATH = ".gerrit/skills/code-review/SKILL.md";
  private static final String COMMON_PATH = ".gerrit/skills/review-common/SKILL.md";
  private static final String BOUNDARIES_PATH = ".gerrit/skills/specialist-boundaries/SKILL.md";
  private final GitRepoFiles files = mock(GitRepoFiles.class);
  private final GerritChange change = mock(GerritChange.class);
  private final ProjectInstructionsAppender appender = new ProjectInstructionsAppender(files);
  private IAiPrompt prompt;
  private String bundledInstructions;
  private JsonObject specialistGuidance;

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
    specialistGuidance =
        JsonParser.parseString(projectResource("specialist-guidance.json")).getAsJsonObject();
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
    verify(files).getFileContent(change, COMMON_PATH);
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
  public void selectedSpecialistsReceiveMatchingGuidanceWithoutChangingRouting() throws Exception {
    String routing = SpecializedReviewAgentDefinitions.triageAgentList();
    for (var entry : specialistGuidance.entrySet()) {
      String name = entry.getKey();
      String path = ".gerrit/skills/" + name + "/SKILL.md";
      var settings = entry.getValue().getAsJsonObject();
      var specialist = specialistPrompt(settings.get("agent").getAsString());
      String original = specialist.getDefaultAiAssistantInstructions();
      when(files.getFileContent(change, path)).thenReturn(projectResource(name + ".md"));

      String actual = appender.append(specialist, change, original);

      assertEquals(
          original
              + "\n\n# Project "
              + name
              + " guidance\n\n"
              + settings.get("guidance").getAsString(),
          actual);
      assertFalse(actual.contains("description:"));
      assertEquals(original, specialist.getDefaultAiAssistantInstructions());
      verify(files).getFileContent(change, path);
    }
    assertEquals(routing, SpecializedReviewAgentDefinitions.triageAgentList());
    verify(files, times(specialistGuidance.size())).getFileContent(change, COMMON_PATH);
    verify(files, times(specialistGuidance.size())).getFileContent(change, BOUNDARIES_PATH);
    verifyNoMoreInteractions(files);
  }

  @Test
  public void securityGuidanceDoesNotAffectTestabilityOrSingleAgentReviews() throws Exception {
    when(files.getFileContent(change, ".gerrit/skills/security/SKILL.md"))
        .thenReturn(projectResource("security.md"));
    var security = specialistPrompt("SECURITY");
    var testability = specialistPrompt("TESTABILITY");
    String testabilityInstructions = testability.getDefaultAiAssistantInstructions();

    assertTrue(
        appender
            .append(security, change, security.getDefaultAiAssistantInstructions())
            .endsWith(
                specialistGuidance.getAsJsonObject("security").get("guidance").getAsString()));
    assertSame(
        testabilityInstructions, appender.append(testability, change, testabilityInstructions));
    assertSame(bundledInstructions, appender.append(prompt, change, bundledInstructions));
    verify(files).getFileContent(change, ".gerrit/skills/security/SKILL.md");
    verify(files).getFileContent(change, ".gerrit/skills/testability/SKILL.md");
    verify(files).getFileContent(change, SKILL_PATH);
    verify(files, times(3)).getFileContent(change, COMMON_PATH);
    verify(files, times(2)).getFileContent(change, BOUNDARIES_PATH);
    verifyNoMoreInteractions(files);
  }

  @Test
  public void missingAndInvalidSpecialistSettingsPreserveBundledInstructions() throws Exception {
    var specialist = specialistPrompt("TESTABILITY");
    String original = specialist.getDefaultAiAssistantInstructions();
    String path = ".gerrit/skills/testability/SKILL.md";
    var invalid = JsonParser.parseString(projectResource("invalid-skills.json")).getAsJsonObject();
    for (var entry : invalid.entrySet()) {
      when(files.getFileContent(change, path))
          .thenReturn(entry.getValue().getAsString().replace("code-review", "testability"));

      assertSame(entry.getKey(), original, appender.append(specialist, change, original));
    }
    when(files.getFileContent(change, path)).thenThrow(new FileNotFoundException(path));
    assertSame(original, appender.append(specialist, change, original));
  }

  @Test
  public void specialistGuidanceDoesNotLeakBetweenProjectsOrBranchUpdates() throws Exception {
    var specialist = specialistPrompt("SECURITY");
    String original = specialist.getDefaultAiAssistantInstructions();
    String path = ".gerrit/skills/security/SKILL.md";
    GerritChange otherChange = mock(GerritChange.class);
    when(files.getFileContent(change, path))
        .thenReturn(projectResource("security.md"))
        .thenReturn(
            projectResource("code-review.md").replace("name: code-review", "name: security"))
        .thenThrow(new FileNotFoundException(path));
    when(files.getFileContent(otherChange, path)).thenThrow(new FileNotFoundException(path));

    assertTrue(
        appender
            .append(specialist, change, original)
            .endsWith(
                specialistGuidance.getAsJsonObject("security").get("guidance").getAsString()));
    assertSame(original, appender.append(specialist, otherChange, original));
    assertTrue(
        appender
            .append(specialist, change, original)
            .endsWith(projectResource("guidance.md").strip()));
    assertSame(original, appender.append(specialist, change, original));
  }

  @Test
  public void specialistAliasesResolveToTheBundledSkillDirectory() throws Exception {
    String path = ".gerrit/skills/code-quality/SKILL.md";
    when(files.getFileContent(change, path)).thenReturn(projectResource("code-quality.md"));
    var specialist = specialistPrompt(" code-quality ");

    assertTrue(
        appender
            .append(specialist, change, specialist.getDefaultAiAssistantInstructions())
            .endsWith(
                specialistGuidance.getAsJsonObject("code-quality").get("guidance").getAsString()));
    verify(files).getFileContent(change, path);
    verify(files).getFileContent(change, COMMON_PATH);
    verify(files).getFileContent(change, BOUNDARIES_PATH);
    verifyNoMoreInteractions(files);
  }

  @Test
  public void unknownSpecialistsDoNotReadUnregisteredSettings() throws Exception {
    for (String name :
        Arrays.asList(null, "COMMIT_MESSAGE", "UNKNOWN", "../security", "code-review")) {
      assertSame(
          bundledInstructions,
          appender.append(specialistPrompt(name), change, bundledInstructions));
    }
    verify(files, times(5)).getFileContent(change, COMMON_PATH);
    verify(files, times(5)).getFileContent(change, BOUNDARIES_PATH);
    verify(files).getFileContent(change, ".gerrit/skills/commit-message-review/SKILL.md");
    verifyNoMoreInteractions(files);
  }

  @Test
  public void rejectsSkillPathsOutsideTheProjectSkillRoot() {
    var skills = new ProjectSkillInstructions(files);
    for (String name : Arrays.asList(null, "../security", "/security", "security/SKILL.md")) {
      assertThrows(IllegalArgumentException.class, () -> skills.load(change, name));
    }
    verifyNoInteractions(files);
  }

  @Test
  public void promptsWithoutDeclaredSkillsDoNotLoadGuidance() {
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

  private AiPromptSpecializedReviewAgent specialistPrompt(String name) {
    Configuration config = mock(Configuration.class);
    when(config.getAgentSpecializationLevel())
        .thenReturn(AgentSpecializationLevel.SPECIALIZED_AGENTS);
    when(config.getMultiAgentMode()).thenReturn(true);
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    ChangeSetData data = new ChangeSetData(1);
    data.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT);
    data.setSpecializedAgentReview(true);
    data.setSpecializedAgentName(name);
    SpecializedReviewAgentDefinitions.findByName(name)
        .ifPresent(
            definition -> data.setSpecializedAgentInstructions(definition.getInstructions()));
    return (AiPromptSpecializedReviewAgent)
        AiPromptFactory.getAiPrompt(config, data, change, mock(ICodeContextPolicy.class));
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
