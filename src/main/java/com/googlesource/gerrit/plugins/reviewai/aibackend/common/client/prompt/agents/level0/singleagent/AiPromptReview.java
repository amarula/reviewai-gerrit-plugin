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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.*;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptSections;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class AiPromptReview extends AiPromptBase implements IAiPrompt {
  private static final String RULE_NUMBER_PREFIX = "RULE #";

  // Lazy static cache for callers that do not have an instance.
  private static volatile java.util.Map<String, Object> cachedReviewPrompts;

  private static synchronized java.util.Map<String, Object> getCachedReviewPrompts() {
    if (cachedReviewPrompts == null) {
      cachedReviewPrompts = getJsonPromptValues("agents/level0/single-agent/prompts");
    }
    return cachedReviewPrompts;
  }

  /** Returns a prompt value without requiring an instance. */
  public static String staticPrompt(String key) {
    return (String) getCachedReviewPrompts().get(key);
  }


  private final ICodeContextPolicy codeContextPolicy;

  public AiPromptReview(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    this.codeContextPolicy = codeContextPolicy;
    loadPromptMap("agents/level0/single-agent/prompts");
    log.debug("AiPromptReview initialized for change ID: {}", change.getFullChangeId());
  }

  public static String getRoutedReviewAgentInstructions(ReviewAssistantStage stage) {
    return switch (stage) {
      case REVIEW_COMMIT_MESSAGE -> staticPrompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ROUTED_COMMIT_MESSAGE_AGENT");
      case REVIEW_CODE,
          CLASSIFY_REVIEW_FEEDBACK,
          REVIEW_CONCERNS,
          FIND_NEW_ISSUES,
          REVIEW_REITERATED,
          REVIEW_SPECIALIZED_TRIAGE,
          REVIEW_SPECIALIZED_AGENT,
          REVIEW_SPECIALIZED_CONSOLIDATION,
          REVIEW_SPECIALIZED_HISTORICAL_REPETITION,
          REVIEW_SPECIALIZED_CONFLICT_RESOLUTION,
          REVIEW_SPECIALIZED_VERIFICATION ->
          staticPrompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_ROUTED_PATCHSET_AGENT");
    };
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    addReviewInstructions(instructions);
    if (includeCommitMessageReviewRequirement()) {
      instructions.add(getReviewPromptCommitMessages());
      instructions.add(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES_LOCATION"));
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
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_ROLE"),
            config
                .getConfiguredAiSystemPromptInstructions()
                .orElseGet(
                    () ->
                        resolveReviewInstructions(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_TASKS")))));
    sections.addAll(buildConditionLabelSections());
    sections.addAll(buildReviewFeedbackSections());
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_SCOPE_AND_REVIEW_CONSTRAINTS"),
            getScopeAndReviewConstraints()));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RULES"), getAiAssistantInstructionsReview()));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_ADDITIONAL_REVIEW_GUIDELINES"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_GUIDELINES")));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_MANDATORY_RESPONSE_FORMAT"),
            getMandatoryResponseFormat()));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_EXAMPLE_RESPONSE"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_EXAMPLES")));
    sections.add(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_FIELD_DEFINITIONS"),
            getPatchSetReviewPrompt()
                + getCommitMessageLocationInstructionsIfNeeded()));
    if (includeCommitMessageReviewRequirement()) {
      sections.add(
          buildSection(
              prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_COMMIT_MESSAGE_REVIEW_REQUIREMENT"),
              getReviewPromptCommitMessages()));
    }

    String compiledInstructions = joinWithDoubleNewLine(sections);
    log.debug("Compiled AI Assistant Review Instructions: {}", compiledInstructions);
    return compiledInstructions;
  }

  private String getCommitMessageLocationInstructionsIfNeeded() {
    if (!includeCommitMessageReviewRequirement()) {
      return "";
    }
    return "\n\n" + prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_COMMIT_MESSAGES_LOCATION");
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
                        prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_RULES"),
                        getAiAssistantInstructionsReview(),
                        prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_GUIDELINES"),
                        prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_FORMAT"),
                        prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_EXAMPLES")))),
            getPatchSetReviewPrompt()));
    log.debug("Review instructions formed: {}", instructions);
  }

  protected String getScopeAndReviewConstraints() {
    List<String> constraints = new ArrayList<>(List.of(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NO_FILE_CONTEXT")));
    List<String> commonInstructions = new ArrayList<>();
    addCommonAiAssistantInstructions(commonInstructions, false);
    commonInstructions.stream()
        .filter(instruction -> !constraints.contains(instruction))
        .forEach(constraints::add);
    return joinWithDoubleNewLine(constraints);
  }

  protected String getMandatoryResponseFormat() {
    return joinWithNewLine(
        splitString(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_FORMAT").strip(), "\n").stream()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.startsWith("//"))
            .toList());
  }

  protected String buildSection(String title, String body) {
    return AiPromptSections.buildSection(title, body);
  }

  protected List<String> buildReviewFeedbackSections() {
    AgentSpecializationLevel level =
        config == null ? null : config.getAgentSpecializationLevel();
    if (level == null) {
      return List.of();
    }
    ReviewFeedbackMemory memory = changeSetData.getReviewFeedbackMemory();
    if (memory == null
        || (memory.getGenericFeedback() == null
            && (memory.getConcernFeedback() == null
                || memory.getConcernFeedback().isEmpty()))) {
      return List.of();
    }
    return List.of(
        buildSection(
            prompt("DEFAULT_AI_REVIEW_SECTION_TITLE_REVIEW_FEEDBACK"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_REVIEW_FEEDBACK_CONTEXT")
                + "\n\n"
                + getGson().toJson(memory)));
  }

  protected String getAiAssistantInstructionsReview(boolean... ruleFilter) {
    return getAiAssistantInstructionsReview(true, ruleFilter);
  }

  protected String getAiAssistantInstructionsReviewWithoutDirectives(boolean... ruleFilter) {
    return getAiAssistantInstructionsReview(false, ruleFilter);
  }

  private String getAiAssistantInstructionsReview(
      boolean includeConfiguredDirectives, boolean... ruleFilter) {
    // Rules are applied by default unless the corresponding ruleFilter values is set to false
    List<String> rules = new ArrayList<>();
    codeContextPolicy.addCodeContextPolicyAwareAssistantRule(rules);
    rules.addAll(
        List.of(
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_HISTORY"),
            prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_FOCUS_PATCH_SET")));
    if (includeConfiguredDirectives && config.getDirective() != null) {
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
