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

import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernWorkflowInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackClassificationInput;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

public class ReviewFollowUpSkillsTest {
  @Test
  public void newIssueFinderPreservesCompleteSystemPrompt() throws Exception {
    assertRendered(
        "new-issue-finder/system.md",
        prompt(ReviewAssistantStage.FIND_NEW_ISSUES).getDefaultAiAssistantInstructions());
  }

  @Test
  public void newIssueFinderPreservesCompleteRequestWithWorkflowData() throws Exception {
    assertRendered(
        "new-issue-finder/request.md",
        prompt(ReviewAssistantStage.FIND_NEW_ISSUES).getDefaultAiThreadReviewMessage(""));
  }

  @Test
  public void feedbackClassifierPreservesCompleteSystemPrompt() throws Exception {
    assertRendered(
        "review-feedback/system.md",
        prompt(ReviewAssistantStage.CLASSIFY_REVIEW_FEEDBACK).getDefaultAiAssistantInstructions());
  }

  @Test
  public void feedbackClassifierPreservesCompleteRequestWithMemoryAndComments() throws Exception {
    assertRendered(
        "review-feedback/request.md",
        prompt(ReviewAssistantStage.CLASSIFY_REVIEW_FEEDBACK).getDefaultAiThreadReviewMessage(""));
  }

  private IAiPrompt prompt(ReviewAssistantStage stage) throws Exception {
    var context = getGson().fromJson(readFixture("follow-up/context.json"), JsonObject.class);
    ChangeSetData data = new ChangeSetData(1);
    data.setForcedStagedReview(true);
    data.setReviewAssistantStage(stage);
    data.setConcernWorkflowInput(
        getGson().fromJson(readFixture("concern-review/input.json"), ConcernWorkflowInput.class));
    data.setReviewFeedbackClassificationInput(
        getGson().fromJson(context.get("feedback_input"), ReviewFeedbackClassificationInput.class));
    data.setConditionLabels(
        Map.of(
            context.get("label_name").getAsString(),
            getGson().fromJson(context.get("condition_label"), GerritConditionLabel.class)));
    Configuration config = mock(Configuration.class);
    when(config.getAiReviewApplicableIf())
        .thenReturn(context.get("review_condition").getAsString());
    return AiPromptFactory.getAiPrompt(
        config, data, mock(GerritChange.class), mock(ICodeContextPolicy.class));
  }

  private void assertRendered(String name, String actual) throws Exception {
    assertEquals(readFixture(name).strip(), actual);
  }

  private String readFixture(String name) throws Exception {
    String path = "__files/skills/" + name;
    try (var resource = getClass().getClassLoader().getResourceAsStream(path)) {
      assertNotNull(path, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
