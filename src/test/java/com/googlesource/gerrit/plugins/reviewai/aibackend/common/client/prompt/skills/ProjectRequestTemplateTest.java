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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptRequests;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage.AiPromptReviewCommitMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.feedback.AiPromptReviewFeedbackClassification;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackClassificationInput;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ProjectRequestTemplateTest {
  private static final String PATH = ".gerrit/skills/review-common/assets/request.md";
  private final GitRepoFiles files = mock(GitRepoFiles.class);
  private final GerritChange change = mock(GerritChange.class);
  private final Configuration config = mock(Configuration.class);
  private final ProjectRequestTemplate templates = new ProjectRequestTemplate(files);
  private AiPromptReview prompt;
  private String patch;
  private String bundled;

  @Before
  public void setUp() throws Exception {
    patch = resource("request-patch.diff");
    prompt =
        new AiPromptReview(config, new ChangeSetData(1), change, mock(ICodeContextPolicy.class));
    bundled = prompt.getDefaultAiThreadReviewMessage(patch);
    templates.configure(prompt, change);
  }

  @Test
  public void replacesTemplateAndFormatsPatchSetOnlyOnce() throws Exception {
    when(files.getFileContent(change, PATH)).thenReturn(resource("request-template.md"));

    assertEquals(resource("request-expected.md"), prompt.getDefaultAiThreadReviewMessage(patch));
    assertEquals(
        ReviewAgentSkills.resource("review-common", "assets/request.md").strip(),
        new AiPromptReview(config, new ChangeSetData(2), change, mock(ICodeContextPolicy.class))
            .getDefaultAiMessageReview());
    verify(files).getFileContent(change, PATH);
  }

  @Test
  public void missingFilePreservesBundledTemplate() throws Exception {
    when(files.getFileContent(change, PATH)).thenThrow(new FileNotFoundException(PATH));

    assertEquals(bundled, prompt.getDefaultAiThreadReviewMessage(patch));
  }

  @Test
  public void nullFilePreservesBundledTemplate() {
    assertEquals(bundled, prompt.getDefaultAiThreadReviewMessage(patch));
  }

  @Test
  public void invalidTemplatesFallBackWithoutDroppingPatchSet() throws Exception {
    var invalid =
        JsonParser.parseString(resource("invalid-request-templates.json")).getAsJsonObject();
    for (var entry : invalid.entrySet()) {
      when(files.getFileContent(change, PATH)).thenReturn(entry.getValue().getAsString());

      assertEquals(entry.getKey(), bundled, prompt.getDefaultAiThreadReviewMessage(patch));
    }
  }

  @Test
  public void readsUpdatedAndDeletedTemplatesWithoutCaching() throws Exception {
    String alternate = resource("alternate-request-template.md");
    when(files.getFileContent(change, PATH))
        .thenReturn(resource("request-template.md"), alternate)
        .thenThrow(new FileNotFoundException(PATH));

    assertEquals(resource("request-expected.md"), prompt.getDefaultAiThreadReviewMessage(patch));
    assertEquals(alternate.strip().formatted(patch), prompt.getDefaultAiThreadReviewMessage(patch));
    assertEquals(bundled, prompt.getDefaultAiThreadReviewMessage(patch));
    verify(files, times(3)).getFileContent(change, PATH);
  }

  @Test
  public void templatesDoNotLeakBetweenChanges() throws Exception {
    GerritChange otherChange = mock(GerritChange.class);
    String alternate = resource("alternate-request-template.md");
    when(files.getFileContent(change, PATH)).thenReturn(resource("request-template.md"));
    when(files.getFileContent(otherChange, PATH)).thenReturn(alternate);
    AiPromptReview other =
        new AiPromptReview(
            config, new ChangeSetData(2), otherChange, mock(ICodeContextPolicy.class));
    templates.configure(other, otherChange);

    assertEquals(alternate.strip().formatted(patch), other.getDefaultAiThreadReviewMessage(patch));
    assertEquals(resource("request-expected.md"), prompt.getDefaultAiThreadReviewMessage(patch));
    verify(files).getFileContent(change, PATH);
    verify(files).getFileContent(otherChange, PATH);
  }

  @Test
  public void explicitCommentRequestDoesNotLoadCommonTemplate() throws Exception {
    ChangeSetData data = new ChangeSetData(1);
    String request = resource("guidance.md");
    data.setAiDataPrompt(request);
    var chat = new AiPromptRequests(config, data, change, mock(ICodeContextPolicy.class));
    String original = chat.getDefaultAiThreadReviewMessage(patch);
    templates.configure(chat, change);

    assertEquals(original, chat.getDefaultAiThreadReviewMessage(patch));
    verifyNoInteractions(files);
  }

  @Test
  public void stageTemplatesReceiveTheirOwnInputWithoutLoadingTheCommonTemplate() throws Exception {
    var input =
        getGson()
            .fromJson(
                resource("request-feedback-input.json"), ReviewFeedbackClassificationInput.class);
    for (String skill : List.of("commit-message-review", "review-feedback")) {
      String template =
          resource(
                  skill.equals("review-feedback")
                      ? "feedback-request-template.md"
                      : "commit-message-request-template.md")
              .strip();
      IAiPrompt stage = stagePrompt(skill, input);
      String path = ".gerrit/skills/" + skill + "/assets/request.md";
      when(files.getFileContent(change, path)).thenReturn(template);
      templates.configure(stage, change);

      String argument = skill.equals("review-feedback") ? getGson().toJson(input) : patch;
      assertEquals(template.formatted(argument), stage.getDefaultAiThreadReviewMessage(patch));
      verify(files).getFileContent(change, path);
      verifyNoMoreInteractions(files);
      clearInvocations(files);
    }
  }

  @Test
  public void invalidAndMissingStageTemplatesPreserveTheirBundledRequests() throws Exception {
    var invalid =
        JsonParser.parseString(resource("invalid-request-templates.json")).getAsJsonObject();
    for (String skill : List.of("commit-message-review", "review-feedback")) {
      IAiPrompt stage = stagePrompt(skill, null);
      String original = stage.getDefaultAiThreadReviewMessage(patch);
      templates.configure(stage, change);
      String path = ".gerrit/skills/" + skill + "/assets/request.md";
      assertEquals(original, stage.getDefaultAiThreadReviewMessage(patch));
      for (var entry : invalid.entrySet()) {
        when(files.getFileContent(change, path)).thenReturn(entry.getValue().getAsString());
        assertEquals(entry.getKey(), original, stage.getDefaultAiThreadReviewMessage(patch));
      }
      when(files.getFileContent(change, path)).thenThrow(new FileNotFoundException(path));
      assertEquals(original, stage.getDefaultAiThreadReviewMessage(patch));
    }
  }

  @Test
  public void unavailableRepositoryAccessPreservesBundledTemplate() {
    prompt =
        new AiPromptReview(config, new ChangeSetData(1), change, mock(ICodeContextPolicy.class));
    new ProjectRequestTemplate(null).configure(prompt, change);

    assertEquals(bundled, prompt.getDefaultAiThreadReviewMessage(patch));
    verifyNoInteractions(files);
  }

  @Test
  public void customPromptImplementationsAreUnaffected() {
    IAiPrompt other = mock(IAiPrompt.class);

    templates.configure(other, change);

    verifyNoInteractions(other, files);
  }

  private String resource(String name) throws Exception {
    String path = "__files/skills/project/" + name;
    try (var stream = getClass().getClassLoader().getResourceAsStream(path)) {
      assertNotNull(path, stream);
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private IAiPrompt stagePrompt(String skill, ReviewFeedbackClassificationInput input) {
    ChangeSetData data = new ChangeSetData(1);
    data.setReviewFeedbackClassificationInput(input);
    ICodeContextPolicy policy = mock(ICodeContextPolicy.class);
    return skill.equals("review-feedback")
        ? new AiPromptReviewFeedbackClassification(config, data, change, policy)
        : new AiPromptReviewCommitMessage(config, data, change, policy);
  }
}
