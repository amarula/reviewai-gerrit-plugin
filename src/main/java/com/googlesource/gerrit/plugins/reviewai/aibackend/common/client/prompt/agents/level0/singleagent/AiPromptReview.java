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
import static org.apache.commons.collections4.CollectionUtils.isEmpty;
import static org.apache.commons.collections4.MapUtils.isEmpty;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptSections;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills.ReviewAgentSkills;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AiPromptReview extends AiPromptBase implements IAiPrompt {
  private static final String RULE_NUMBER_PREFIX = "RULE #";

  private final ICodeContextPolicy codeContextPolicy;

  public AiPromptReview(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(config, changeSetData, change, codeContextPolicy);
    this.codeContextPolicy = codeContextPolicy;
    log.debug("AiPromptReview initialized for change ID: {}", change.getFullChangeId());
  }

  @Override
  public void addAiAssistantInstructions(List<String> instructions) {
    addReviewInstructions(instructions);
    if (includeCommitMessageReviewRequirement()) {
      instructions.add(getReviewPromptCommitMessages());
      instructions.add(getCommitMessageLocationInstructions());
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
            "Role",
            config
                .getConfiguredAiSystemPromptInstructions()
                .orElseGet(() -> resolveReviewInstructions(reviewSection("Role")))));
    sections.addAll(buildConditionLabelSections());
    sections.addAll(buildReviewFeedbackSections());
    sections.add(buildSection("Scope and Review Constraints", getScopeAndReviewConstraints()));
    sections.add(buildSection("Mandatory Rules", getAiAssistantInstructionsReview()));
    sections.add(
        buildSection(
            "Additional Review Guidelines", reviewSection("Additional Review Guidelines")));
    sections.add(buildSection("MANDATORY Response Format", getMandatoryResponseFormat()));
    sections.add(
        buildSection("Example response", getDefaultAiAssistantInstructionsResponseExamples()));
    sections.add(
        buildSection(
            "Field Definitions",
            getPatchSetReviewPrompt() + getCommitMessageLocationInstructionsIfNeeded()));
    if (includeCommitMessageReviewRequirement()) {
      sections.add(
          buildSection("Commit Message Review Requirement", getReviewPromptCommitMessages()));
    }

    String compiledInstructions = joinWithDoubleNewLine(sections);
    log.debug("Compiled AI Assistant Review Instructions: {}", compiledInstructions);
    return compiledInstructions;
  }

  private String getCommitMessageLocationInstructionsIfNeeded() {
    if (!includeCommitMessageReviewRequirement()) {
      return "";
    }
    return "\n\n" + getCommitMessageLocationInstructions();
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
                        reviewSection("Mandatory Rules"),
                        getAiAssistantInstructionsReview(),
                        reviewSection("Additional Review Guidelines"),
                        prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_FORMAT"),
                        getDefaultAiAssistantInstructionsResponseExamples()))),
            getPatchSetReviewPrompt()));
    log.debug("Review instructions formed: {}", instructions);
  }

  protected static String reviewSection(String title) {
    return ReviewAgentSkills.section("code-review", title);
  }

  protected static String getCommitMessageLocationInstructions() {
    return ReviewAgentSkills.section("commit-message-review", "Locations");
  }

  @Override
  public String getDefaultAiAssistantInstructionsResponseExamples() {
    return ReviewAgentSkills.resource("code-review", "assets/response-example.md");
  }

  protected String getScopeAndReviewConstraints() {
    List<String> constraints =
        new ArrayList<>(List.of(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_NO_FILE_CONTEXT")));
    List<String> commonInstructions = new ArrayList<>();
    addCommonAiAssistantInstructions(commonInstructions, false);
    commonInstructions.stream()
        .filter(instruction -> !constraints.contains(instruction))
        .forEach(constraints::add);
    return joinWithDoubleNewLine(constraints);
  }

  protected String getMandatoryResponseFormat() {
    return joinWithNewLine(
        splitString(prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_RESPONSE_FORMAT").strip(), "\n")
            .stream()
            .map(String::strip)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.startsWith("//"))
            .toList());
  }

  protected String buildSection(String title, String body) {
    return AiPromptSections.buildSection(title, body);
  }

  protected List<String> buildReviewFeedbackSections() {
    AgentSpecializationLevel level = config == null ? null : config.getAgentSpecializationLevel();
    if (level == null) {
      return List.of();
    }
    ReviewFeedbackMemory memory = changeSetData.getReviewFeedbackMemory();
    if (memory == null
        || (memory.getGenericFeedback() == null
            && isEmpty(memory.getConcernFeedback())
            && isEmpty(memory.getDisabledReviewScopes())
            && isEmpty(memory.getDisabledSpecializedAgents()))) {
      return List.of();
    }
    return List.of(
        buildSection(
            "Distilled User Review Feedback",
            reviewSection("Distilled User Review Feedback") + "\n\n" + getGson().toJson(memory)));
  }

  protected String getAiAssistantInstructionsReview(boolean... ruleFilter) {
    // Built-in rules are applied by default unless the corresponding ruleFilter value is set to
    // false. Each ruleFilter position refers to a fixed built-in rule slot (code context policy
    // rule, history rule, focus rule), even when the current code context policy adds no rule.
    // Configured directives are never filtered.
    List<String> codeContextPolicyRules = new ArrayList<>();
    codeContextPolicy.addCodeContextPolicyAwareAssistantRule(codeContextPolicyRules);
    List<List<String>> builtInRuleSlots =
        List.of(
            codeContextPolicyRules,
            List.of(reviewSection("History")),
            List.of(reviewSection("Patch Scope")));
    List<String> rules = new ArrayList<>();
    for (int i = 0; i < builtInRuleSlots.size(); i++) {
      if (i >= ruleFilter.length || ruleFilter[i]) {
        rules.addAll(builtInRuleSlots.get(i));
      }
    }
    if (config.getDirective() != null) {
      rules.addAll(config.getDirective());
    }
    log.debug("Rules used in the assistant: {}", rules);
    return joinWithNewLine(getNumberedList(rules, RULE_NUMBER_PREFIX, COLON_SPACE));
  }
}
