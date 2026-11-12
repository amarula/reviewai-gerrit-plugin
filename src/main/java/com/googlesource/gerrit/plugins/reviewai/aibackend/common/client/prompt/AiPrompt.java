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

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.*;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills.ReviewAgentSkills;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPermittedVotingRange;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.*;
import java.util.stream.Collectors;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class AiPrompt {

  // Reply attributes
  public static final String ATTRIBUTE_ID = "id";
  public static final String ATTRIBUTE_REPLY = "reply";
  public static final String ATTRIBUTE_SCORE = "score";
  public static final String ATTRIBUTE_REPEATED = "repeated";
  public static final String ATTRIBUTE_DUPLICATED = "duplicated";
  public static final String ATTRIBUTE_CONFLICTING = "conflicting";
  public static final String ATTRIBUTE_RELEVANCE = "relevance";
  public static final String ATTRIBUTE_REPEATED_REASON = "repeated_reason";
  public static final String ATTRIBUTE_DUPLICATED_REASON = "duplicated_reason";
  public static final String ATTRIBUTE_CONFLICTING_REASON = "conflicting_reason";
  public static final String ATTRIBUTE_CHANGE_ID = "changeId";
  public static final String ATTRIBUTE_FILENAME = "filename";
  public static final String ATTRIBUTE_LINE_NUMBER = "lineNumber";
  public static final String ATTRIBUTE_CODE_SNIPPET = "codeSnippet";

  public static final List<String> PATCH_SET_REVIEW_REPLY_ATTRIBUTES =
      Collections.unmodifiableList(
          Arrays.asList(
              ATTRIBUTE_REPLY,
              ATTRIBUTE_SCORE,
              ATTRIBUTE_REPEATED,
              ATTRIBUTE_CONFLICTING,
              ATTRIBUTE_RELEVANCE));

  public static final List<String> REQUEST_REPLY_ATTRIBUTES =
      Collections.unmodifiableList(
          Arrays.asList(ATTRIBUTE_REPLY, ATTRIBUTE_ID, ATTRIBUTE_CHANGE_ID));

  // ---- Reply-field descriptions ----

  private Map<String, String> repliesAttributes;

  protected final Configuration config;

  @Setter protected boolean isCommentEvent;

  public AiPrompt(Configuration config) {
    this.config = config;
    log.debug("AiPrompt initialized.");
  }

  protected static String commonSection(String title) {
    return ReviewAgentSkills.section("review-common", title);
  }

  /** Returns the ordered reply-field descriptions from the shared skill. */
  public Map<String, String> getRepliesAttributes() {
    if (repliesAttributes == null) {
      repliesAttributes = new LinkedHashMap<>();
      for (String attribute :
          List.of(
              ATTRIBUTE_REPLY,
              ATTRIBUTE_ID,
              ATTRIBUTE_SCORE,
              ATTRIBUTE_FILENAME,
              ATTRIBUTE_LINE_NUMBER,
              ATTRIBUTE_CODE_SNIPPET,
              ATTRIBUTE_RELEVANCE,
              ATTRIBUTE_CHANGE_ID,
              ATTRIBUTE_REPEATED,
              ATTRIBUTE_DUPLICATED,
              ATTRIBUTE_CONFLICTING)) {
        repliesAttributes.put(attribute, commonSection("Field " + attribute));
      }
    }
    return repliesAttributes;
  }

  // ---- Convenience accessors ----

  public String getDefaultAiSystemPromptInstructions() {
    return commonSection("System");
  }

  public String getDefaultAiReviewPromptDirectives() {
    return commonSection("Directives");
  }

  public String getDefaultAiPromptForceJsonFormat() {
    return commonSection("JSON Response");
  }

  public String getDefaultAiRepliesPromptSpecs() {
    return commonSection("Reply Specifications");
  }

  public String getDefaultAiRepliesPromptInline() {
    return commonSection("Inline Replies");
  }

  public String getDefaultAiRepliesPromptEnforceResponseCheck() {
    return commonSection("Reply Count");
  }

  public String getDefaultAiRequestPromptDiff() {
    return commonSection("Request Diff");
  }

  public String getDefaultAiRequestPromptRequests() {
    return commonSection("Request Data");
  }

  public String getDefaultAiReviewPromptCommitMessages() {
    return ReviewAgentSkills.section("commit-message-review", "Review Requirement");
  }

  public String getDefaultAiReviewPromptInstructionsCommitMessages() {
    return ReviewAgentSkills.section("commit-message-review", "Review Instructions");
  }

  public String getDefaultAiRelevanceRules() {
    return commonSection("Relevance");
  }

  public String getDefaultAiHowToFindCommitMessage() {
    return ReviewAgentSkills.section("commit-message-review", "Commit Message Source");
  }

  // ---- Prompt building methods ----

  public String getReviewPromptCommitMessages() {
    log.debug("Constructing review prompt for commit messages.");
    return joinWithSpace(
        new ArrayList<>(
            List.of(
                String.format(
                    getDefaultAiReviewPromptCommitMessages(), getDefaultAiHowToFindCommitMessage()),
                getDefaultAiReviewPromptInstructionsCommitMessages())));
  }

  protected String buildFieldSpecifications(List<String> filterFields) {
    return buildFieldSpecifications(filterFields, getRepliesAttributes());
  }

  protected String buildFieldSpecifications(
      List<String> filterFields, Map<String, String> replyAttributes) {
    log.debug("Building field specifications for filter fields: {}", filterFields);
    Set<String> orderedFilterFields = new LinkedHashSet<>(filterFields);
    Map<String, String> attributes =
        replyAttributes.entrySet().stream()
            .filter(entry -> orderedFilterFields.contains(entry.getKey()))
            .collect(
                Collectors.toMap(
                    entry -> INLINE_CODE_DELIMITER + entry.getKey() + INLINE_CODE_DELIMITER,
                    Map.Entry::getValue,
                    (oldValue, newValue) -> oldValue,
                    LinkedHashMap::new));
    List<String> fieldDescription =
        attributes.entrySet().stream()
            .map(entry -> entry.getKey() + SPACE + entry.getValue())
            .collect(Collectors.toList());

    return String.format(
        getDefaultAiRepliesPromptSpecs(),
        joinWithComma(attributes.keySet()),
        joinWithSemicolon(fieldDescription));
  }

  public String getPatchSetReviewPromptInstructions() {
    log.debug("Getting patch set review prompt instructions.");
    List<String> attributes = new ArrayList<>(PATCH_SET_REVIEW_REPLY_ATTRIBUTES);
    if (!config.isVotingEnabled()) {
      attributes.remove(ATTRIBUTE_SCORE);
    }
    Map<String, String> replyAttributes = new LinkedHashMap<>(getRepliesAttributes());
    updateScoreDescription(replyAttributes);
    updateRelevanceDescription(replyAttributes);
    return buildFieldSpecifications(attributes, replyAttributes);
  }

  public String getPatchSetReviewPrompt() {
    log.debug("Getting patch set review prompt.");
    return getPatchSetReviewPromptInstructions() + SPACE + getDefaultAiRepliesPromptInline();
  }

  private void updateScoreDescription(Map<String, String> replyAttributes) {
    log.debug("Updating score description.");
    String scoreDescription = replyAttributes.get(ATTRIBUTE_SCORE);
    if (scoreDescription != null && scoreDescription.contains("%s")) {
      String votingRangeDescription =
          getPermittedVotingRange()
              .map(range -> String.format(" from %d to %d", range.getMin(), range.getMax()))
              .orElse("");
      scoreDescription = String.format(scoreDescription, votingRangeDescription);
      replyAttributes.put(ATTRIBUTE_SCORE, scoreDescription);
      log.debug("Updated score description to: {}", scoreDescription);
    }
  }

  protected Optional<GerritPermittedVotingRange> getPermittedVotingRange() {
    return Optional.empty();
  }

  private void updateRelevanceDescription(Map<String, String> replyAttributes) {
    log.debug("Updating relevance description.");
    String relevanceDescription = replyAttributes.get(ATTRIBUTE_RELEVANCE);
    if (relevanceDescription != null && relevanceDescription.contains("%s")) {
      String defaultAiRelevanceRules =
          config.getString(Configuration.KEY_AI_RELEVANCE_RULES, getDefaultAiRelevanceRules());
      relevanceDescription = String.format(relevanceDescription, defaultAiRelevanceRules);
      replyAttributes.put(ATTRIBUTE_RELEVANCE, relevanceDescription);
      log.debug("Updated relevance description to: {}", relevanceDescription);
    }
  }
}
