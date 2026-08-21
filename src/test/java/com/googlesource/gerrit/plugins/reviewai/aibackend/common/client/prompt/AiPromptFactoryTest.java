/*
 * Copyright (c) 2026. The Android Open Source Project
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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.commitmessage.AiPromptReviewCommitMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.patchset.AiPromptReviewCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router.AiPromptReviewAgentRouter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router.AiPromptRoutedReviewAgentRequest;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewAgent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.concerns.AiPromptConcernReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.concerns.AiPromptNewIssueFinder;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernWorkflowInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiPromptFactoryTest {

  @Test
  public void commentEventUsesGenericRequestPrompt() {
    IAiPrompt prompt =
        AiPromptFactory.getAiPrompt(
            mock(Configuration.class),
            new ChangeSetData(1),
            commentEventChange(),
            mock(ICodeContextPolicy.class));

    assertTrue(prompt instanceof AiPromptRequests);
  }

  @Test
  public void routedCommentEventUsesStageAwareRequestPrompt() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedStagedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);

    IAiPrompt prompt =
        AiPromptFactory.getAiPrompt(
            mock(Configuration.class),
            changeSetData,
            commentEventChange(),
            mock(ICodeContextPolicy.class));

    assertTrue(prompt instanceof AiPromptRoutedReviewAgentRequest);
  }

  @Test
  public void routedReviewAgentInstructionsAreLoadedIntoReviewPromptFields() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    AiPromptRoutedReviewAgentRequest request = new AiPromptRoutedReviewAgentRequest(
        mock(Configuration.class),
        changeSetData,
        commentEventChange(),
        mock(ICodeContextPolicy.class));

    Map<String, Object> prompts = AiPrompt.getJsonPromptValues("agents/level1/router/routed-request-prompts");

    assertEquals(
        prompts.get("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ROUTED_COMMIT_MESSAGE_AGENT"),
        request.prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ROUTED_COMMIT_MESSAGE_AGENT"));
  }

  @Test
  public void suggestModeUsesSuggestPrompt() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedReview(true);
    changeSetData.setSuggestMode(true);

    IAiPrompt prompt =
        AiPromptFactory.getAiPrompt(
            mock(Configuration.class),
            changeSetData,
            patchSetEventChange(),
            mock(ICodeContextPolicy.class));

    assertTrue(prompt instanceof AiPromptSuggest);
  }

  @Test
  public void specializedCommitMessageReviewUsesSpecializedPrompt() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedStagedReview(true);
    changeSetData.setSpecializedAgentReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    Configuration config = mock(Configuration.class);
    when(config.getMultiAgentMode()).thenReturn(true);

    IAiPrompt prompt =
        AiPromptFactory.getAiPrompt(
            config, changeSetData, patchSetEventChange(), mock(ICodeContextPolicy.class));

    assertTrue(prompt instanceof AiPromptSpecializedReviewAgent);
    String instructions = prompt.getDefaultAiAssistantInstructions();
    assertTrue(instructions.contains("`concerns`: array of candidate issues"));
    assertFalse(instructions.contains("The answer object includes"));
  }

  @Test
  public void concernReviewStageUsesConcernReviewPrompt() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedStagedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_CONCERNS);
    Configuration config = mock(Configuration.class);
    when(config.getMultiAgentMode()).thenReturn(true);

    IAiPrompt prompt =
        AiPromptFactory.getAiPrompt(
            config, changeSetData, patchSetEventChange(), mock(ICodeContextPolicy.class));

    assertTrue(prompt instanceof AiPromptConcernReview);
    String instructions = prompt.getDefaultAiAssistantInstructions();
    assertTrue(instructions.contains("# Role\n\nYou are a concern reviewer."));
    assertTrue(instructions.contains("# Task\n\nReassess every supplied concern"));
    assertTrue(instructions.contains("# Mandatory Rules\n\nReturn exactly one update"));
    assertTrue(instructions.contains("# MANDATORY Response Format\n\nReturn only JSON"));
  }

  @Test
  public void specializedConcernReviewIncludesSpecialistRole() {
    ReviewerConcerns concerns = new ReviewerConcerns();
    concerns.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SPECIALIZED_AGENT, "CODE_QUALITY"));
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedStagedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_CONCERNS);
    changeSetData.setSpecializedAgentInstructions("Review code quality concerns only.");
    changeSetData.setConditionLabels(
        Map.of(
            "Verified",
            new GerritConditionLabel(List.of((short) 1), "CI verification")));
    changeSetData.setConcernWorkflowInput(
        new ConcernWorkflowInput(concerns, "incremental patch", null));
    Configuration config = mock(Configuration.class);
    when(config.getAiReviewApplicableIf()).thenReturn("label:Verified=+1");

    IAiPrompt prompt =
        AiPromptFactory.getAiPrompt(
            config, changeSetData, patchSetEventChange(), mock(ICodeContextPolicy.class));

    String instructions = prompt.getDefaultAiAssistantInstructions();
    assertTrue(instructions.contains("# Role\n\nReview code quality concerns only."));
    assertTrue(instructions.contains("# Task\n\nReassess every supplied concern"));
    assertTrue(instructions.contains("# Mandatory Rules\n\nReturn exactly one update"));
    assertTrue(instructions.contains("# MANDATORY Response Format\n\nReturn only JSON"));
    assertTrue(instructions.contains("Return exactly one update for every supplied concern"));
    assertTrue(instructions.contains("# Current AI Review Condition"));
    assertTrue(instructions.contains("label:Verified=+1"));
    assertTrue(instructions.contains("# Condition Labels"));
    assertTrue(instructions.contains("- Verified: +1"));
    assertTrue(instructions.contains("Description: CI verification"));
    assertFalse(instructions.contains("candidate issues that may deserve"));
    String request = prompt.getDefaultAiThreadReviewMessage("");
    assertFalse(request.contains("condition_labels"));
  }

  @Test
  public void specializedCommitMessageConcernReviewIncludesCommitMessageRole() {
    ReviewerConcerns concerns = new ReviewerConcerns();
    concerns.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SPECIALIZED_AGENT, "COMMIT_MESSAGE"));
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedStagedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_CONCERNS);
    changeSetData.setConditionLabels(
        Map.of(
            "Verified",
            new GerritConditionLabel(List.of((short) 1), "CI verification")));
    changeSetData.setConcernWorkflowInput(
        new ConcernWorkflowInput(concerns, "incremental patch", null));
    Configuration config = mock(Configuration.class);
    when(config.getAiReviewApplicableIf()).thenReturn("label:Verified=+1");

    IAiPrompt prompt =
        AiPromptFactory.getAiPrompt(
            config, changeSetData, patchSetEventChange(), mock(ICodeContextPolicy.class));

    String commitMessageRole =
        (String)
            AiPrompt.getJsonPromptValues("agents/level1/commit-message/prompts")
                .get("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES");
    String instructions = prompt.getDefaultAiAssistantInstructions();
    assertTrue(instructions.contains("# Role\n\n" + commitMessageRole));
    assertTrue(instructions.contains("# Task\n\nReassess every supplied concern"));
    assertTrue(instructions.contains("# Mandatory Rules\n\nReturn exactly one update"));
    assertTrue(instructions.contains("# MANDATORY Response Format\n\nReturn only JSON"));
    assertTrue(instructions.contains("Return exactly one update for every supplied concern"));
    assertFalse(instructions.contains("candidate issues that may deserve"));
    assertFalse(instructions.contains("# Current AI Review Condition"));
    assertFalse(instructions.contains("# Condition Labels"));
    assertFalse(instructions.contains("- Verified: +1"));
  }

  @Test
  public void newIssueFinderStageUsesReviewerAwarePrompt() {
    ReviewerConcerns concerns = new ReviewerConcerns();
    concerns.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "PATCHSET"));
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedStagedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.FIND_NEW_ISSUES);
    changeSetData.setConditionLabels(
        Map.of(
            "Verified",
            new GerritConditionLabel(List.of((short) 1), "CI verification")));
    changeSetData.setConcernWorkflowInput(
        new ConcernWorkflowInput(concerns, "incremental patch", null));
    Configuration config = mock(Configuration.class);
    when(config.getAiReviewApplicableIf()).thenReturn("label:Verified=+1");

    IAiPrompt prompt =
        AiPromptFactory.getAiPrompt(
            config, changeSetData, patchSetEventChange(), mock(ICodeContextPolicy.class));

    assertTrue(prompt instanceof AiPromptNewIssueFinder);
    Map<String, Object> prompts =
        AiPrompt.getJsonPromptValues("agents/common/new-issue-finder/prompts");
    assertTrue(
        prompt
            .getDefaultAiAssistantInstructions()
            .contains(
                (String) prompts.get("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NEW_ISSUE_FINDER")));
    assertTrue(prompt.getDefaultAiAssistantInstructions().contains("# Condition Labels"));
    assertTrue(prompt.getDefaultAiAssistantInstructions().contains("- Verified: +1"));
  }

  @Test
  public void commitMessageNewIssueFinderOmitsConditionLabels() {
    ReviewerConcerns concerns = new ReviewerConcerns();
    concerns.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "COMMIT_MESSAGE"));
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedStagedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.FIND_NEW_ISSUES);
    changeSetData.setConditionLabels(
        Map.of(
            "Verified",
            new GerritConditionLabel(List.of((short) 1), "CI verification")));
    changeSetData.setConcernWorkflowInput(
        new ConcernWorkflowInput(concerns, "incremental patch", null));
    Configuration config = mock(Configuration.class);
    when(config.getAiReviewApplicableIf()).thenReturn("label:Verified=+1");

    IAiPrompt prompt =
        AiPromptFactory.getAiPrompt(
            config, changeSetData, patchSetEventChange(), mock(ICodeContextPolicy.class));

    String instructions = prompt.getDefaultAiAssistantInstructions();
    assertFalse(instructions.contains("# Current AI Review Condition"));
    assertFalse(instructions.contains("# Condition Labels"));
    assertFalse(instructions.contains("- Verified: +1"));
  }

  @Test
  public void scopedCommitMessageReviewRequiresCommitMessageFilename() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    AiPromptReviewCommitMessage prompt =
        new AiPromptReviewCommitMessage(
            mock(Configuration.class),
            changeSetData,
            patchSetEventChange(),
            mock(ICodeContextPolicy.class));

    String instructions = prompt.getDefaultAiAssistantInstructions();

    assertTrue(instructions.contains("Every commit-message reply MUST identify"));
    assertTrue(instructions.contains("`filename`"));
    assertTrue(instructions.contains("/COMMIT_MSG"));
    assertTrue(instructions.contains("reviewai-topic-change-1/COMMIT_MSG"));
    assertTrue(instructions.contains("\"filename\": \"/COMMIT_MSG\""));
  }

  @Test
  public void singleAgentCommitMessageReviewRequiresCommitMessageFilename() {
    Configuration config = mock(Configuration.class);
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    AiPromptReview prompt =
        new AiPromptReview(
            config,
            new ChangeSetData(1),
            patchSetEventChange(),
            mock(ICodeContextPolicy.class));

    String instructions = prompt.getDefaultAiAssistantInstructions();

    assertTrue(instructions.contains("Every commit-message reply MUST identify"));
    assertTrue(instructions.contains("`filename`"));
    assertTrue(instructions.contains("/COMMIT_MSG"));
    assertTrue(instructions.contains("reviewai-topic-change-1/COMMIT_MSG"));
  }

  @Test
  public void reviewPromptIncludesConfiguredApplicabilityExpression() {
    Configuration config = mock(Configuration.class);
    when(config.getAiReviewApplicableIf())
        .thenReturn("label:Verified=+1 OR label:Code-Review=+2");
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setConditionLabels(
        Map.of(
            "Verified",
            new GerritConditionLabel(java.util.List.of((short) 1), "CI verification"),
            "Code-Review",
            new GerritConditionLabel(java.util.List.of(), "Code quality review")));
    AiPromptReview prompt =
        new AiPromptReview(
            config,
            changeSetData,
            patchSetEventChange(),
            mock(ICodeContextPolicy.class));

    String instructions = prompt.getDefaultAiAssistantInstructions();

    assertTrue(instructions.contains("Current AI Review Condition"));
    assertTrue(instructions.contains("label:Verified=+1 OR label:Code-Review=+2"));
    assertTrue(instructions.contains("Condition Labels"));
    assertTrue(instructions.contains("- Verified: +1"));
    assertTrue(instructions.contains("Description: CI verification"));
    assertTrue(instructions.contains("- Code-Review: no vote"));
    assertTrue(instructions.contains("Description: Code quality review"));
  }

  @Test
  public void reviewPromptOmitsApplicabilitySectionWhenExpressionIsBlank() {
    Configuration config = mock(Configuration.class);
    when(config.getAiReviewApplicableIf()).thenReturn("");
    AiPromptReview prompt =
        new AiPromptReview(
            config,
            new ChangeSetData(1),
            patchSetEventChange(),
            mock(ICodeContextPolicy.class));

    assertFalse(
        prompt.getDefaultAiAssistantInstructions().contains("Current AI Review Condition"));
  }

  @Test
  public void specializedReviewPromptsIncludeConditionLabelsExceptCommitMessage() {
    String applicableIf = "label:Verified=+1";
    Configuration config = mock(Configuration.class);
    when(config.getMultiAgentMode()).thenReturn(true);
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    when(config.getAiReviewApplicableIf()).thenReturn(applicableIf);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setSpecializedAgentName("CORRECTNESS");
    changeSetData.setSpecializedAgentInstructions("Review correctness only.");
    changeSetData.setConditionLabels(
        Map.of(
            "Verified",
            new GerritConditionLabel(List.of((short) 1), "CI verification")));

    for (ReviewAssistantStage stage :
        List.of(
            ReviewAssistantStage.REVIEW_SPECIALIZED_TRIAGE,
            ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT,
            ReviewAssistantStage.REVIEW_SPECIALIZED_CONSOLIDATION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_HISTORICAL_REPETITION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_CONFLICT_RESOLUTION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION)) {
      changeSetData.setReviewAssistantStage(stage);

      String instructions =
          AiPromptFactory.getAiPrompt(
                  config,
                  changeSetData,
                  patchSetEventChange(),
                  mock(ICodeContextPolicy.class))
              .getDefaultAiAssistantInstructions();

      assertTrue(
          stage + " should include the applicability expression",
          instructions.contains(applicableIf));
      assertTrue(
          stage + " should include condition labels", instructions.contains("- Verified: +1"));
    }

    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    changeSetData.setSpecializedAgentName(null);
    changeSetData.setSpecializedAgentReview(true);

    String commitMessageInstructions =
        AiPromptFactory.getAiPrompt(
                config,
                changeSetData,
                patchSetEventChange(),
                mock(ICodeContextPolicy.class))
            .getDefaultAiAssistantInstructions();

    assertFalse(commitMessageInstructions.contains(applicableIf));
    assertFalse(commitMessageInstructions.contains("Condition Labels"));
  }

  @Test
  public void suggestPromptsAreLoadedFromResources() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedReview(true);
    changeSetData.setSuggestMode(true);
    AiPromptSuggest prompt =
        new AiPromptSuggest(
            mock(Configuration.class),
            changeSetData,
            patchSetEventChange(),
            mock(ICodeContextPolicy.class));
    Map<String, Object> prompts = AiPrompt.getJsonPromptValues("promptsAiSuggest");

    assertEquals(
        prompts.get("DEFAULT_AI_SUGGEST_INSTRUCTIONS_ROLE"),
        prompt.prompt("DEFAULT_AI_SUGGEST_INSTRUCTIONS_ROLE"));
    String instructions = prompt.getDefaultAiAssistantInstructions();
    assertTrue(instructions.contains("Suggestion Task"));
    assertTrue(instructions.contains("exactly ONE all-inclusive commit-message Suggested Edit"));
    assertTrue(instructions.contains("filename"));
    assertTrue(instructions.contains("lineNumber"));
    assertTrue(instructions.contains("codeSnippet"));
    assertTrue(instructions.contains("/COMMIT_MSG"));
    assertTrue(instructions.contains("including the first line"));
    assertTrue(prompt.getDefaultAiThreadReviewMessage("patch").contains("every negative review reply"));
  }

  @Test
  public void suggestPatchsetScopeUsesOnlyPatchsetTaskPrompt() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedReview(true);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.PATCHSET);
    AiPromptSuggest prompt =
        new AiPromptSuggest(
            mock(Configuration.class),
            changeSetData,
            patchSetEventChange(),
            mock(ICodeContextPolicy.class));
    Map<String, Object> prompts = AiPrompt.getJsonPromptValues("promptsAiSuggest");

    String instructions = prompt.getDefaultAiAssistantInstructions();

    assertTrue(
        instructions.contains(
            prompts.get("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_PATCHSET").toString()));
    assertFalse(
        instructions.contains(
            prompts.get("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_COMMIT_MESSAGE").toString()));
  }

  @Test
  public void suggestCommitMessageScopeUsesOnlyCommitMessageTaskPrompt() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedReview(true);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.COMMIT_MESSAGE);
    AiPromptSuggest prompt =
        new AiPromptSuggest(
            mock(Configuration.class),
            changeSetData,
            patchSetEventChange(),
            mock(ICodeContextPolicy.class));
    Map<String, Object> prompts = AiPrompt.getJsonPromptValues("promptsAiSuggest");

    String instructions = prompt.getDefaultAiAssistantInstructions();

    assertFalse(
        instructions.contains(
            prompts.get("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_PATCHSET").toString()));
    assertTrue(
        instructions.contains(
            prompts.get("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_COMMIT_MESSAGE").toString()));
  }

  @Test
  public void suggestMultiAgentUsesReviewStageTaskPrompt() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setForcedReview(true);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    Configuration config = mock(Configuration.class);
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    when(config.getMultiAgentMode()).thenReturn(true);
    AiPromptSuggest prompt =
        new AiPromptSuggest(
            config, changeSetData, patchSetEventChange(), mock(ICodeContextPolicy.class));
    Map<String, Object> prompts = AiPrompt.getJsonPromptValues("promptsAiSuggest");

    String instructions = prompt.getDefaultAiAssistantInstructions();

    assertFalse(
        instructions.contains(
            prompts.get("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_PATCHSET").toString()));
    assertTrue(
        instructions.contains(
            prompts.get("DEFAULT_AI_SUGGEST_INSTRUCTIONS_TASK_COMMIT_MESSAGE").toString()));
  }

  @Test
  public void routedReviewAgentInstructionsReplaceDefaultSystemPrompt() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_CODE);
    changeSetData.setCommentPropertiesSize(1);
    Configuration config = mock(Configuration.class);
    when(config.getAiSystemPromptInstructions(anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    AiPromptRoutedReviewAgentRequest prompt =
        new AiPromptRoutedReviewAgentRequest(
            config, changeSetData, commentEventChange(), mock(ICodeContextPolicy.class));

    String instructions = prompt.getDefaultAiAssistantInstructions();

    assertTrue(instructions.startsWith("You are ReviewPatchsetAgent."));
    assertFalse(instructions.contains((String) AiPrompt.getJsonPromptValues("prompts").get("DEFAULT_AI_SYSTEM_PROMPT_INSTRUCTIONS")));
  }

  @Test
  public void patchsetAgentPromptsAreLoadedFromResource() {
    new AiPromptReviewCode(
        mock(Configuration.class),
        new ChangeSetData(1),
        patchSetEventChange(),
        mock(ICodeContextPolicy.class));
    Map<String, Object> prompts =
        AiPrompt.getJsonPromptValues("agents/level1/patchset/prompts");

    String reviewTasks = (String) prompts.get("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_TASKS");
    assertFalse(prompts.get("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_TASKS").toString().isEmpty());
  }

  @Test
  public void reviewAgentRouterPromptsAreLoadedFromResource() {
    AiPromptReviewAgentRouter routerPrompt =
        new AiPromptReviewAgentRouter(mock(Configuration.class));
    Map<String, Object> prompts = AiPrompt.getJsonPromptValues("agents/level1/router/prompts");

    assertEquals(
        prompts.get("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_AGENT_ROUTER"),
        routerPrompt.getDefaultAiAssistantInstructions());
    assertEquals(
        String.format(
            prompts.get("DEFAULT_AI_MESSAGE_REVIEW_AGENT_ROUTER").toString(), "request"),
        routerPrompt.getDefaultAiThreadReviewMessage("request"));
  }

  private GerritChange commentEventChange() {
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    return change;
  }

  private GerritChange patchSetEventChange() {
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(false);
    when(change.getFullChangeId()).thenReturn("change~1");
    return change;
  }
}
