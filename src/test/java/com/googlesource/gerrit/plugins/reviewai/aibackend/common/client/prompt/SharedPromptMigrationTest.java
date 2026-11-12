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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Change;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyNone;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class SharedPromptMigrationTest {
  @Parameterized.Parameters(name = "{0}")
  public static List<String> reviewers() {
    return List.of(
        "chat",
        "moderator-chat",
        "patchset-suggestions",
        "commit-message-suggestions",
        "combined-suggestions");
  }

  @Parameterized.Parameter public String reviewer;

  @Test
  public void preservesCompleteSystemPrompt() throws Exception {
    assertRendered("system", prompt().getDefaultAiAssistantInstructions());
  }

  @Test
  public void preservesAdditionalInstructions() throws Exception {
    List<String> instructions = new ArrayList<>();
    prompt().addAiAssistantInstructions(instructions);
    assertRendered("additional", String.join("\n\n", instructions));
  }

  @Test
  public void preservesRequestTemplateAndPatch() throws Exception {
    assertRendered(
        "request",
        prompt()
            .getDefaultAiThreadReviewMessage(
                readResource("__files/langchain/newIssueFullPatch.txt")));
  }

  private AiPromptBase prompt() throws Exception {
    var context =
        getGson().fromJson(readResource("__files/skills/follow-up/context.json"), JsonObject.class);
    Configuration config = mock(Configuration.class);
    when(config.getAgentSpecializationLevel()).thenReturn(AgentSpecializationLevel.SINGLE_AGENT);
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    when(config.getAiSystemPromptInstructions(org.mockito.ArgumentMatchers.anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(config.getAiReviewApplicableIf())
        .thenReturn(context.get("review_condition").getAsString());
    when(config.getDirective())
        .thenReturn(
            List.of(
                context
                    .getAsJsonObject("feedback_input")
                    .getAsJsonObject("current_memory")
                    .get("generic_feedback")
                    .getAsString()));
    ChangeSetData data = new ChangeSetData(1);
    data.setReviewFeedbackMemory(
        getGson()
            .fromJson(
                readResource("__files/feedback/reviewFeedbackMemory.json"),
                ReviewFeedbackMemory.class));
    data.setConditionLabels(
        Map.of(
            context.get("label_name").getAsString(),
            getGson().fromJson(context.get("condition_label"), GerritConditionLabel.class)));
    data.setModeratorFeaturesAllowed(reviewer.equals("moderator-chat"));
    data.setCommentPropertiesSize(2);
    data.setAiDataPrompt(readResource("__files/langchain/chatMemoryUserMessage.txt"));
    if (reviewer.equals("patchset-suggestions")) data.setReviewScope(ReviewScope.PATCHSET);
    if (reviewer.equals("commit-message-suggestions"))
      data.setReviewScope(ReviewScope.COMMIT_MESSAGE);
    GerritChange change = mock(GerritChange.class);
    when(change.getChangeKey()).thenReturn(Change.key("I123456"));
    var policy = new CodeContextPolicyNone(config);
    return reviewer.endsWith("suggestions")
        ? new AiPromptSuggest(config, data, change, policy)
        : new AiPromptRequests(config, data, change, policy);
  }

  private void assertRendered(String name, String actual) throws Exception {
    String path = "__files/skills/shared/" + reviewer + ".json";
    var expected = getGson().fromJson(readResource(path), JsonObject.class);
    assertEquals(expected.get(name).getAsString(), actual);
  }

  private String readResource(String path) throws Exception {
    try (var resource = getClass().getClassLoader().getResourceAsStream(path)) {
      assertNotNull(path, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
