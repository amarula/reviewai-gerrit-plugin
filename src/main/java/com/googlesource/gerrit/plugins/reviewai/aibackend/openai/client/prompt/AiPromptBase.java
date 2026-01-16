/*
 * Copyright (c) 2025. The Android Open Source Project
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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.CopilotInstructions;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.openai.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.*;

@Slf4j
public abstract class AiPromptBase extends AiPrompt implements IAiPrompt {
  public static String DEFAULT_AI_ASSISTANT_NAME;
  public static String DEFAULT_AI_ASSISTANT_DESCRIPTION;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_FILE_CONTEXT;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NO_FILE_CONTEXT;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_FORMAT;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_EXAMPLES;
  public static String DEFAULT_AI_MESSAGE_REQUEST_RESEND_FORMATTED;
  public static String DEFAULT_AI_MESSAGE_REVIEW;

  protected final ChangeSetData changeSetData;
  protected final GerritChange change;

  private final ICodeContextPolicy codeContextPolicy;
  private final CopilotInstructions copilotInstructions;

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
    this.copilotInstructions = new CopilotInstructions(change);
    loadDefaultPrompts("promptsOpenAi");
    log.debug("Initialized AiPromptBase with change ID: {}", change.getFullChangeId());
  }

  public String getDefaultAiAssistantDescription() {
    String description = String.format(DEFAULT_AI_ASSISTANT_DESCRIPTION, change.getProjectName());
    log.debug("Generated AI Assistant Description: {}", description);
    return description;
  }

  public abstract void addAiAssistantInstructions(List<String> instructions);

  public abstract String getAiRequestDataPrompt();

  public String getDefaultAiAssistantInstructions() {
    List<String> instructions =
        new ArrayList<>(
            List.of(
                config.getAiSystemPromptInstructions(DEFAULT_AI_SYSTEM_PROMPT_INSTRUCTIONS)
                    + DOT));
    codeContextPolicy.addCodeContextPolicyAwareAssistantInstructions(instructions);
    this.copilotInstructions.addCopilotInstructions(instructions);
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
      String defaultMessage = String.format(DEFAULT_AI_MESSAGE_REVIEW, patchSet);
      log.debug("Default Thread Review Message used: {}", defaultMessage);
      return defaultMessage;
    }
  }
}
