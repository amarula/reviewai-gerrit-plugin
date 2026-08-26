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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPermittedVotingRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.*;

@Slf4j
public abstract class AiPromptBase extends AiPrompt implements IAiPrompt {

  // Lazy static cache for callers that don't have an instance (e.g. CodeContextPolicyNone).
  private static volatile Map<String, Object> cachedPromptsAi;

  private static synchronized Map<String, Object> getCachedPromptsAi() {
    if (cachedPromptsAi == null) {
      cachedPromptsAi = getJsonPromptValues("promptsAi");
    }
    return cachedPromptsAi;
  }

  /** Returns a prompt value from promptsAi.json without requiring an instance. */
  public static String staticPrompt(String key) {
    return (String) getCachedPromptsAi().get(key);
  }

  // ---- Instance fields ----

  protected final ChangeSetData changeSetData;
  protected final GerritChange change;
  protected String defaultAiMessageReview;

  private final ICodeContextPolicy codeContextPolicy;

  public AiPromptBase(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config);
    this.changeSetData = changeSetData;
    this.change = change;
    this.codeContextPolicy = codeContextPolicy;
    this.isCommentEvent = change.getIsCommentEvent();
    loadPromptMap("promptsAi");
    this.defaultAiMessageReview = prompt("DEFAULT_AI_MESSAGE_REVIEW");
    log.debug("Initialized AiPromptBase with change ID: {}", change.getFullChangeId());
  }

  // ---- Convenience accessors ----

  public String getDefaultAiAssistantName() {
    return prompt("DEFAULT_AI_ASSISTANT_NAME");
  }

  public String getDefaultAiAssistantDescription() {
    return prompt("DEFAULT_AI_ASSISTANT_DESCRIPTION");
  }

  public String getDefaultAiAssistantInstructionsFileContext() {
    return prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_FILE_CONTEXT");
  }

  public String getDefaultAiAssistantInstructionsNoFileContext() {
    return prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NO_FILE_CONTEXT");
  }

  public String getDefaultAiAssistantInstructionsResponseFormat() {
    return prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_FORMAT");
  }

  public String getDefaultAiAssistantInstructionsResponseExamples() {
    return prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_EXAMPLES");
  }

  public String getDefaultAiMessageRequestResendFormatted() {
    return prompt("DEFAULT_AI_MESSAGE_REQUEST_RESEND_FORMATTED");
  }

  public String getDefaultAiMessageReview() {
    return prompt("DEFAULT_AI_MESSAGE_REVIEW");
  }

  // ---- Abstract and instance methods ----

  public abstract void addAiAssistantInstructions(List<String> instructions);

  public abstract String getAiRequestDataPrompt();

  @Override
  protected Optional<GerritPermittedVotingRange> getPermittedVotingRange() {
    return Optional.ofNullable(changeSetData.getPermittedVotingRange());
  }

  protected List<String> buildConditionLabelSections() {
    if (Boolean.FALSE.equals(changeSetData.getAiReviewConditionMet())) {
      return List.of();
    }
    String applicableIf = config.getAiReviewApplicableIf();
    if (applicableIf == null || applicableIf.isBlank()) {
      return List.of();
    }
    List<String> sections = new ArrayList<>();
    sections.add(AiPromptSections.buildSection("Current AI Review Condition", applicableIf));
    String conditionLabels =
        AiPromptConditionLabelFormatter.format(
            changeSetData.getConditionLabels(), key -> new Localizer(config).getText(key));
    if (!conditionLabels.isEmpty()) {
      sections.add(AiPromptSections.buildSection("Condition Labels", conditionLabels));
    }
    return sections;
  }

  protected void addCommonAiAssistantInstructions(
      List<String> instructions, boolean includeSystemPromptInstructions) {
    if (includeSystemPromptInstructions) {
      instructions.add(
          config.getAiSystemPromptInstructions(getDefaultAiSystemPromptInstructions()) + DOT);
    }
    codeContextPolicy.addCodeContextPolicyAwareAssistantInstructions(instructions);
  }

  @Override
  public String getDefaultAiSystemPromptInstructions() {
    return prompt("DEFAULT_AI_SYSTEM_PROMPT_INSTRUCTIONS");
  }

  public String getDefaultAiAssistantInstructions() {
    List<String> instructions = new ArrayList<>();
    addCommonAiAssistantInstructions(instructions, true);
    addAiAssistantInstructions(instructions);
    String compiledInstructions = joinWithSpace(instructions);
    log.debug("Compiled AI Assistant Instructions: {}", compiledInstructions);
    return compiledInstructions;
  }

  public String getDefaultAiThreadReviewMessage(String patchSet) {
    String aiRequestDataPrompt = getAiRequestDataPrompt();
    if (aiRequestDataPrompt != null && !aiRequestDataPrompt.isEmpty()) {
      log.debug("Request User Prompt retrieved: {}", aiRequestDataPrompt);
      return aiRequestDataPrompt;
    } else {
      String defaultMessage = String.format(defaultAiMessageReview, patchSet);
      log.debug("Default Thread Review Message used: {}", defaultMessage);
      return defaultMessage;
    }
  }
}
