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

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientPatchSetHelper.filterPatchWithoutCommitMessage;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Change;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router.AiPromptReviewAgentRouter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinitions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills.ProjectRequestTemplate;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernWorkflowInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackClassificationInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ProjectPipelineSkillInstructionsTest {
  @Rule public GerritUiPromptRule gerritUiPrompts = new GerritUiPromptRule();

  @Parameterized.Parameters(name = "{0}")
  public static List<Object[]> scenarios() throws Exception {
    List<Object[]> cases = new ArrayList<>();
    for (var entry : JsonParser.parseString(resource("pipeline-cases.json")).getAsJsonArray()) {
      var scenario = entry.getAsJsonObject();
      cases.add(new Object[] {scenario.get("id").getAsString(), scenario});
    }
    return cases;
  }

  @Parameterized.Parameter public String scenario;

  @Parameterized.Parameter(1)
  public JsonObject testCase;

  private final GitRepoFiles files = mock(GitRepoFiles.class);
  private final GerritChange change = mock(GerritChange.class);
  private final ProjectInstructionsAppender appender = new ProjectInstructionsAppender(files);
  private IAiPrompt prompt;
  private AiPromptReviewAgentRouter router;
  private List<String> skills;
  private String original;
  private String request;
  private String patch;
  private String requestArgument;

  @Before
  public void setUp() throws Exception {
    skills = List.of(getGson().fromJson(testCase.get("skills"), String[].class));
    patch = readResource("__files/langchain/newIssueFullPatch.txt");
    Configuration config = mock(Configuration.class);
    AgentSpecializationLevel level =
        testCase.has("mode")
            ? AgentSpecializationLevel.valueOf(testCase.get("mode").getAsString())
            : AgentSpecializationLevel.SCOPED_AGENTS;
    when(config.getAgentSpecializationLevel()).thenReturn(level);
    when(config.getMultiAgentMode()).thenReturn(level != AgentSpecializationLevel.SINGLE_AGENT);
    when(config.getAiReviewCommitMessages())
        .thenReturn(!testCase.has("commit_review") || testCase.get("commit_review").getAsBoolean());
    when(config.getAiSystemPromptInstructions(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    if (scenario.equals("router")) {
      router = new AiPromptReviewAgentRouter(config);
      original = router.getDefaultAiAssistantInstructions();
      request = router.getDefaultAiThreadReviewMessage(patch);
      requestArgument = patch;
      return;
    }
    when(change.getIsCommentEvent()).thenReturn(flag("comment"));
    when(change.getChangeKey()).thenReturn(Change.key("I123456"));
    ChangeSetData data = new ChangeSetData(1);
    data.setCommentPropertiesSize(1);
    data.setReviewAssistantStage(ReviewAssistantStage.valueOf(testCase.get("stage").getAsString()));
    data.setForcedStagedReview(flag("staged"));
    data.setSuggestMode(flag("suggest"));
    data.setSpecializedAgentReview(flag("specialized"));
    data.setReviewFeedbackClassificationInput(
        getGson()
            .fromJson(
                resource("request-feedback-input.json"), ReviewFeedbackClassificationInput.class));
    if (testCase.has("specialist")) {
      var definition =
          SpecializedReviewAgentDefinitions.findByName(testCase.get("specialist").getAsString())
              .orElseThrow();
      data.setSpecializedAgentName(definition.normalizedName());
      data.setSpecializedAgentInstructions(definition.getInstructions());
    }
    if (testCase.has("reviewer")) {
      data.setForcedStagedReview(true);
      ReviewerConcerns concerns = new ReviewerConcerns();
      concerns.setReviewer(getGson().fromJson(testCase.get("reviewer"), ConcernReviewerId.class));
      data.setConcernWorkflowInput(new ConcernWorkflowInput(concerns, patch, patch));
    }
    prompt = AiPromptFactory.getAiPrompt(config, data, change, mock(ICodeContextPolicy.class));
    assertEquals(skills, prompt.getProjectSkillNames());
    original = prompt.getDefaultAiAssistantInstructions();
    request = prompt.getDefaultAiThreadReviewMessage(patch);
    requestArgument =
        switch (scenario) {
          case "scoped-code" -> filterPatchWithoutCommitMessage(change, patch);
          case "feedback" -> getGson().toJson(data.getReviewFeedbackClassificationInput());
          default ->
              testCase.has("reviewer") ? getGson().toJson(data.getConcernWorkflowInput()) : patch;
        };
  }

  @Test
  public void customRequestTemplatesApplyOnlyToPromptsThatUseThem() throws Exception {
    for (var item : scenarios()) {
      var other = (JsonObject) item[1];
      if (!other.get("request_skill").isJsonNull()) {
        String skill = other.get("request_skill").getAsString();
        when(files.getFileContent(change, requestPath(skill))).thenReturn(requestTemplate(skill));
      }
    }
    configureRequestTemplate();

    String skill = requestSkill();
    String expected = skill == null ? request : requestTemplate(skill).formatted(requestArgument);
    assertEquals(expected, renderRequest());
    verifyOnlyRequestTemplate();
  }

  @Test
  public void invalidAndMissingRequestTemplatesPreserveBundledRequests() throws Exception {
    configureRequestTemplate();
    assertEquals(request, renderRequest());
    String skill = requestSkill();
    if (skill != null) {
      String invalid =
          JsonParser.parseString(resource("invalid-request-templates.json"))
              .getAsJsonObject()
              .get("numeric argument")
              .getAsString();
      when(files.getFileContent(change, requestPath(skill))).thenReturn(invalid);
      assertEquals(request, renderRequest());
      when(files.getFileContent(change, requestPath(skill))).thenThrow(new FileNotFoundException());
      assertEquals(request, renderRequest());
    }
  }

  private void configureRequestTemplate() {
    var templates = new ProjectRequestTemplate(files);
    if (router == null) {
      templates.configure(prompt, change);
    } else {
      templates.configureRouting(router, change);
    }
  }

  private String requestSkill() {
    return testCase.get("request_skill").isJsonNull()
        ? null
        : testCase.get("request_skill").getAsString();
  }

  private String requestPath(String skill) {
    return ".gerrit/skills/" + skill + "/assets/request.md";
  }

  private String requestTemplate(String skill) throws Exception {
    return resource("pipeline-request-template.md").replace("SKILL_NAME", skill).strip();
  }

  private String renderRequest() {
    return router == null
        ? prompt.getDefaultAiThreadReviewMessage(patch)
        : router.getDefaultAiThreadReviewMessage(patch);
  }

  private void verifyOnlyRequestTemplate() throws Exception {
    if (requestSkill() == null) {
      verifyNoInteractions(files);
    } else {
      verify(files).getFileContent(change, requestPath(requestSkill()));
      verifyNoMoreInteractions(files);
    }
  }

  @Test
  public void appendsOnlyApplicableGuidanceAndPreservesBundledContracts() throws Exception {
    String expected = original;
    for (String skill : skills) {
      when(files.getFileContent(change, path(skill)))
          .thenReturn(resource("pipeline-skill.md").replace("SKILL_NAME", skill));
      expected += guidanceSection(skill);
    }

    assertEquals(expected, append());
    assertEquals(
        original,
        router == null
            ? prompt.getDefaultAiAssistantInstructions()
            : router.getDefaultAiAssistantInstructions());
    assertEquals(
        request,
        router == null
            ? prompt.getDefaultAiThreadReviewMessage(patch)
            : router.getDefaultAiThreadReviewMessage(patch));
    verifyOnlyDeclaredFiles();
  }

  @Test
  public void missingFilesPreserveExistingPrompts() throws Exception {
    when(files.getFileContent(eq(change), anyString())).thenThrow(new FileNotFoundException());

    assertEquals(original, append());
    verifyOnlyDeclaredFiles();
  }

  @Test
  public void invalidSharedGuidanceDoesNotBlockStageGuidance() throws Exception {
    String expected = original;
    for (String skill : skills) {
      when(files.getFileContent(change, path(skill)))
          .thenReturn(resource("pipeline-skill.md").replace("SKILL_NAME", skill));
      if (!skill.equals("review-common")) {
        expected += guidanceSection(skill);
      }
    }
    when(files.getFileContent(change, path("review-common")))
        .thenReturn(
            JsonParser.parseString(resource("invalid-skills.json"))
                .getAsJsonObject()
                .get("wrong name")
                .getAsString());

    assertEquals(expected, append());
    verifyOnlyDeclaredFiles();
  }

  private String append() {
    return router == null
        ? appender.append(prompt, change, original)
        : appender.appendRouting(change, original);
  }

  private void verifyOnlyDeclaredFiles() throws Exception {
    for (String skill : skills) {
      verify(files).getFileContent(change, path(skill));
    }
    if (prompt instanceof AiPromptRequests) {
      verify(files).getFileContent(change, ".gerrit/ai-instructions.md");
    }
    verifyNoMoreInteractions(files);
  }

  private boolean flag(String name) {
    return testCase.has(name) && testCase.get(name).getAsBoolean();
  }

  private static String path(String skill) {
    return ".gerrit/skills/" + skill + "/SKILL.md";
  }

  private static String guidanceSection(String skill) throws Exception {
    return "\n\n# Project "
        + skill
        + " guidance\n\n"
        + resource("pipeline-guidance.md").replace("SKILL_NAME", skill).strip();
  }

  private static String resource(String name) throws Exception {
    return readResource("__files/skills/project/" + name);
  }

  private static String readResource(String path) throws Exception {
    try (var resource =
        ProjectPipelineSkillInstructionsTest.class.getClassLoader().getResourceAsStream(path)) {
      assertNotNull(path, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
