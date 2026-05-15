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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.openai.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithSpace;

@Slf4j
public class AiPromptRequests extends AiPromptBase implements IAiPrompt {
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REQUESTS;

  public AiPromptRequests(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    loadDefaultPrompts("promptsOpenAiRequests");
    log.debug("AiPromptRequests initialized for change ID: {}", change.getFullChangeId());
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    instructions.addAll(
        List.of(
            DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REQUESTS,
            getCommentRequestPrompt(changeSetData.getCommentPropertiesSize())));
    log.debug("AI Assistant Instructions for requests added: {}", instructions);
  }

  @Override
  public String getAiRequestDataPrompt() {
    if (changeSetData == null) {
      log.warn("ChangeSetData is null, returning no prompt");
      return null;
    }
    String requestDataPrompt = changeSetData.getAiDataPrompt();
    log.debug("AI Request Data Prompt retrieved: {}", requestDataPrompt);
    return requestDataPrompt;
  }

  @Override
  public String getDefaultAiThreadReviewMessage(String patchSet) {
    String requestDataPrompt = getAiRequestDataPrompt();
    if (requestDataPrompt == null || requestDataPrompt.isEmpty()) {
      return super.getDefaultAiThreadReviewMessage(patchSet);
    }

    List<String> promptSections = new ArrayList<>();
    if (patchSet != null && !patchSet.isEmpty()) {
      promptSections.add(DEFAULT_AI_REQUEST_PROMPT_DIFF);
      promptSections.add(String.format("```%s```", patchSet));
    }
    promptSections.add(DEFAULT_AI_REQUEST_PROMPT_REQUESTS);
    promptSections.add(requestDataPrompt);
    String prompt = joinWithSpace(promptSections);
    log.debug("AI thread review message for requests: {}", prompt);
    return prompt;
  }

  private String getCommentRequestPrompt(int commentPropertiesSize) {
    log.debug(
        "Constructing AI comment request prompt for {} comment properties.",
        commentPropertiesSize);
    return joinWithSpace(
        new ArrayList<>(
            List.of(
                buildFieldSpecifications(REQUEST_REPLY_ATTRIBUTES),
                DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_FORMAT,
                DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_EXAMPLES,
                DEFAULT_AI_REPLIES_PROMPT_INLINE,
                String.format(
                    DEFAULT_AI_REPLIES_PROMPT_ENFORCE_RESPONSE_CHECK, commentPropertiesSize))));
  }
}
