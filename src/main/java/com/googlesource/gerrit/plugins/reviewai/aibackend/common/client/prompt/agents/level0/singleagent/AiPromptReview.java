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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptBase;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.*;

@Slf4j
public class AiPromptReview extends AiPromptBase implements IAiPrompt {
  private static final String RULE_NUMBER_PREFIX = "RULE #";

  public static String DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE;
  public static String DEFAULT_AI_REVIEW_SECTION_TITLE_SCOPE_AND_REVIEW_CONSTRAINTS;
  public static String DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RULES;
  public static String DEFAULT_AI_REVIEW_SECTION_TITLE_ADDITIONAL_REVIEW_GUIDELINES;
  public static String DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RESPONSE_FORMAT;
  public static String DEFAULT_AI_REVIEW_SECTION_TITLE_EXAMPLE_RESPONSE;
  public static String DEFAULT_AI_REVIEW_SECTION_TITLE_FIELD_DEFINITIONS;
  public static String DEFAULT_AI_REVIEW_SECTION_TITLE_COMMIT_MESSAGE_REVIEW_REQUIREMENT;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_TASKS;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_RULES;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_GUIDELINES;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_DONT_GUESS_CODE;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ON_DEMAND_REQUEST;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_HISTORY;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_FOCUS_PATCH_SET;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ROUTED_PATCHSET_AGENT;
  public static String DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ROUTED_COMMIT_MESSAGE_AGENT;

  private final ICodeContextPolicy codeContextPolicy;

  public AiPromptReview(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    this.codeContextPolicy = codeContextPolicy;
    loadDefaultPrompts("agents/level0/single-agent/prompts");
    log.debug("AiPromptReview initialized for change ID: {}", change.getFullChangeId());
  }

  public static String getRoutedReviewAgentInstructions(ReviewAssistantStage stage) {
    return switch (stage) {
      case REVIEW_COMMIT_MESSAGE -> DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ROUTED_COMMIT_MESSAGE_AGENT;
      case REVIEW_CODE, REVIEW_REITERATED ->
          DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ROUTED_PATCHSET_AGENT;
    };
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    addReviewInstructions(instructions);
    if (includeCommitMessageReviewRequirement()) {
      instructions.add(getReviewPromptCommitMessages());
    }
    log.debug("AI Assistant Review Instructions added: {}", instructions);
  }

  @Override
  public String getAiRequestDataPrompt() {
    log.debug("No specific request data prompt for reviews.");
    return null;
  }

  @Override
  public String getDefaultAiAssistantInstructions() {
    List<String> sections = new ArrayList<>();
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE,
            config
                .getConfiguredAiSystemPromptInstructions()
                .orElseGet(
                    () ->
                        resolveReviewInstructions(DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_TASKS))));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_SCOPE_AND_REVIEW_CONSTRAINTS,
            getScopeAndReviewConstraints()));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RULES, getAiAssistantInstructionsReview()));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_ADDITIONAL_REVIEW_GUIDELINES,
            DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_GUIDELINES));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RESPONSE_FORMAT,
            getMandatoryResponseFormat()));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_EXAMPLE_RESPONSE,
            DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_EXAMPLES));
    sections.add(
        buildSection(
            DEFAULT_AI_REVIEW_SECTION_TITLE_FIELD_DEFINITIONS, getPatchSetReviewPrompt()));
    if (includeCommitMessageReviewRequirement()) {
      sections.add(
          buildSection(
              DEFAULT_AI_REVIEW_SECTION_TITLE_COMMIT_MESSAGE_REVIEW_REQUIREMENT,
              getReviewPromptCommitMessages()));
    }

    String compiledInstructions = joinWithDoubleNewLine(sections);
    log.debug("Compiled AI Assistant Review Instructions: {}", compiledInstructions);
    return compiledInstructions;
  }

  protected boolean includeCommitMessageReviewRequirement() {
    return config.getAiReviewCommitMessages();
  }

  protected String resolveCommitMessageInstructions(String fallbackPrompt) {
    return GerritUiPromptLoader.resolveCommitMessageInstructions(fallbackPrompt);
  }

  private String resolveReviewInstructions(String fallbackPrompt) {
    return GerritUiPromptLoader.resolveReviewInstructions(fallbackPrompt);
  }

  protected void addReviewInstructions(List<String> instructions) {
    instructions.addAll(
        List.of(
            joinWithNewLine(
                new ArrayList<>(
                    List.of(
                        DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_RULES,
                        getAiAssistantInstructionsReview(),
                        DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_GUIDELINES,
                        DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_FORMAT,
                        DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_EXAMPLES))),
            getPatchSetReviewPrompt()));
    log.debug("Review instructions formed: {}", instructions);
  }

  protected String getScopeAndReviewConstraints() {
    List<String> constraints = new ArrayList<>(List.of(DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NO_FILE_CONTEXT));
    List<String> commonInstructions = new ArrayList<>();
    addCommonAiAssistantInstructions(commonInstructions, false);
    commonInstructions.stream()
        .filter(instruction -> !constraints.contains(instruction))
        .forEach(constraints::add);
    return joinWithDoubleNewLine(constraints);
  }

  protected String getMandatoryResponseFormat() {
    return joinWithNewLine(
        splitString(DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_FORMAT.strip(), "\n").stream()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.startsWith("//"))
            .toList());
  }

  protected String buildSection(String title, String body) {
    return "# " + title + "\n\n" + body.strip();
  }

  protected String getAiAssistantInstructionsReview(boolean... ruleFilter) {
    // Rules are applied by default unless the corresponding ruleFilter values is set to false
    List<String> rules = new ArrayList<>();
    codeContextPolicy.addCodeContextPolicyAwareAssistantRule(rules);
    rules.addAll(
        List.of(
            DEFAULT_AI_ASSISTANT_INSTRUCTIONS_HISTORY,
            DEFAULT_AI_ASSISTANT_INSTRUCTIONS_FOCUS_PATCH_SET));
    if (config.getDirective() != null) {
      rules.addAll(config.getDirective());
    }
    log.debug("Rules used in the assistant: {}", rules);
    return joinWithNewLine(
        getNumberedList(
            IntStream.range(0, rules.size())
                .filter(i -> i >= ruleFilter.length || ruleFilter[i])
                .mapToObj(rules::get)
                .collect(Collectors.toList()),
            RULE_NUMBER_PREFIX,
            COLON_SPACE));
  }
}
