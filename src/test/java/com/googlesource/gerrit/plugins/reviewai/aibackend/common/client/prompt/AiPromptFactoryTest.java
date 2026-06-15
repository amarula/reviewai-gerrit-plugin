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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.patchset.AiPromptReviewCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router.AiPromptReviewAgentRouter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router.AiPromptRoutedReviewAgentRequest;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.util.Map;
import org.junit.Test;

public class AiPromptFactoryTest {

  @Test
  public void commentEventUsesGenericRequestPrompt() {
    IAiPrompt prompt =
        AiPromptFactory.getAiPrompt(
            mock(Configuration.class),
            new ChangeSetData(1, -1, 1),
            commentEventChange(),
            mock(ICodeContextPolicy.class));

    assertTrue(prompt instanceof AiPromptRequests);
  }

  @Test
  public void routedCommentEventUsesStageAwareRequestPrompt() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
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
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    new AiPromptRoutedReviewAgentRequest(
        mock(Configuration.class),
        changeSetData,
        commentEventChange(),
        mock(ICodeContextPolicy.class));

    Map<String, Object> prompts = AiPrompt.getJsonPromptValues("agents/level1/router/routed-request-prompts");

    assertEquals(
        prompts.get("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ROUTED_COMMIT_MESSAGE_AGENT"),
        AiPromptReview.getRoutedReviewAgentInstructions(
            ReviewAssistantStage.REVIEW_COMMIT_MESSAGE));
  }

  @Test
  public void suggestModeUsesSuggestPrompt() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
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
  public void suggestPromptsAreLoadedFromResources() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
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
        AiPromptSuggest.DEFAULT_AI_SUGGEST_INSTRUCTIONS_ROLE);
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
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
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
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
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
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
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
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
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
    assertFalse(instructions.contains(AiPrompt.DEFAULT_AI_SYSTEM_PROMPT_INSTRUCTIONS));
  }

  @Test
  public void patchsetAgentPromptsAreLoadedFromResource() {
    new AiPromptReviewCode(
        mock(Configuration.class),
        new ChangeSetData(1, -1, 1),
        patchSetEventChange(),
        mock(ICodeContextPolicy.class));
    Map<String, Object> prompts =
        AiPrompt.getJsonPromptValues("agents/level1/patchset/prompts");

    assertEquals(
        prompts.get("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_TASKS"),
        AiPromptReview.DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_TASKS);
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
