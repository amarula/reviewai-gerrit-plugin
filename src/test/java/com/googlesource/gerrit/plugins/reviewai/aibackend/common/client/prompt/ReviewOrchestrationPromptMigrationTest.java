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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyOnDemand;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router.AiPromptReviewAgentRouter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router.AiPromptRoutedReviewAgentRequest;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.*;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class ReviewOrchestrationPromptMigrationTest {
  @Rule public GerritUiPromptRule gerritUiPrompts = new GerritUiPromptRule();

  @Parameterized.Parameters(name = "{0}")
  public static List<String> reviewers() {
    return List.of(
        "router",
        "patchset-follow-up",
        "commit-message-follow-up",
        "triage",
        "specialist",
        "commit-message-specialist",
        "consolidation",
        "historical-repetition",
        "conflict-resolution",
        "verification");
  }

  @Parameterized.Parameter public String reviewer;

  @Test
  public void preservesCompleteSystemPrompt() throws Exception {
    assertRendered(
        "system",
        reviewer.equals("router")
            ? new AiPromptReviewAgentRouter(mock(Configuration.class))
                .getDefaultAiAssistantInstructions()
            : prompt().getDefaultAiAssistantInstructions());
  }

  @Test
  public void preservesRequestTemplateAndPatch() throws Exception {
    String input = readResource("__files/langchain/newIssueFullPatch.txt");
    if (reviewer.equals("router")) {
      var router = new AiPromptReviewAgentRouter(mock(Configuration.class));
      assertRendered("request", router.getDefaultAiThreadReviewMessage(input));
      assertRendered("empty_request", router.getDefaultAiThreadReviewMessage(null));
    } else {
      assertRendered("request", prompt().getDefaultAiThreadReviewMessage(input));
    }
  }

  private AiPromptBase prompt() throws Exception {
    var context =
        getGson().fromJson(readResource("__files/skills/follow-up/context.json"), JsonObject.class);
    Configuration config = mock(Configuration.class);
    when(config.getAgentSpecializationLevel())
        .thenReturn(AgentSpecializationLevel.SPECIALIZED_AGENTS);
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
    data.setReviewAssistantStage(
        reviewer.equals("commit-message-follow-up")
            ? ReviewAssistantStage.REVIEW_COMMIT_MESSAGE
            : ReviewAssistantStage.REVIEW_CODE);
    data.setCommentPropertiesSize(1);
    if (reviewer.equals("specialist")) {
      var specialist = SpecializedReviewAgentDefinitions.findByName("CORRECTNESS").orElseThrow();
      data.setSpecializedAgentName(specialist.getName());
      data.setSpecializedAgentInstructions(specialist.getInstructions());
    }
    data.setSpecializedAgentCustomInstructions(
        context
            .getAsJsonObject("feedback_input")
            .getAsJsonObject("current_memory")
            .get("generic_feedback")
            .getAsString());
    GerritChange change = mock(GerritChange.class);
    when(change.getChangeKey()).thenReturn(Change.key("I123456"));
    var policy = new CodeContextPolicyOnDemand(config);
    return switch (reviewer) {
      case "patchset-follow-up", "commit-message-follow-up" ->
          new AiPromptRoutedReviewAgentRequest(config, data, change, policy);
      case "triage" -> new AiPromptSpecializedReviewTriage(config, data, change, policy);
      case "specialist", "commit-message-specialist" ->
          new AiPromptSpecializedReviewAgent(config, data, change, policy);
      case "consolidation" -> new AiPromptSpecializedConsolidation(config, data, change, policy);
      case "historical-repetition" ->
          new AiPromptSpecializedHistoricalRepetition(config, data, change, policy);
      case "conflict-resolution" ->
          new AiPromptSpecializedConflictResolution(config, data, change, policy);
      case "verification" -> new AiPromptSpecializedVerification(config, data, change, policy);
      default -> throw new IllegalArgumentException(reviewer);
    };
  }

  private void assertRendered(String name, String actual) throws Exception {
    String path = "__files/skills/orchestration/" + reviewer + ".json";
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
