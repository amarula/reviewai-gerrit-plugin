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

import com.google.gson.reflect.TypeToken;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPermittedVotingRange;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.utils.FileUtils;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.stream.Collectors;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.*;

@Slf4j
public class AiPrompt {
  private static final String PROMPT_EXTENDS_ATTRIBUTE = "$extends";

  // Reply attributes (compile-time constants, not JSON-loaded)
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

  // ---- Instance prompt storage (no reflection) ----

  private final Map<String, Object> promptValues = new LinkedHashMap<>();
  private Map<String, String> repliesAttributes;

  protected final Configuration config;

  @Setter protected boolean isCommentEvent;

  public AiPrompt(Configuration config) {
    this.config = config;
    loadPromptMap("prompts");
    log.debug("AiPrompt initialized.");
  }

  // ---- Prompt value access ----

  /** Returns a string prompt value by its JSON key. */
  protected String prompt(String key) {
    return (String) promptValues.get(key);
  }

  /** Returns the replies-attributes map, which is a nested JSON object in prompts.json. */
  @SuppressWarnings("unchecked")
  public Map<String, String> getRepliesAttributes() {
    if (repliesAttributes == null) {
      Object raw = promptValues.get("DEFAULT_AI_REPLIES_ATTRIBUTES");
      if (raw instanceof Map) {
        repliesAttributes = new LinkedHashMap<>((Map<String, String>) raw);
      } else {
        repliesAttributes = new LinkedHashMap<>();
      }
    }
    return repliesAttributes;
  }

  /**
   * Loads a JSON prompt file into this instance's promptValues map.
   * Later loads override earlier values for the same key, which correctly
   * handles subclass overrides (e.g. DEFAULT_AI_SYSTEM_PROMPT_INSTRUCTIONS).
   */
  protected void loadPromptMap(String promptFilename) {
    Map<String, Object> values = getJsonPromptValues(promptFilename);
    Object repliesAttr = values.remove("DEFAULT_AI_REPLIES_ATTRIBUTES");
    promptValues.putAll(values);
    if (repliesAttr instanceof Map) {
      @SuppressWarnings("unchecked")
      Map<String, String> replies = (Map<String, String>) repliesAttr;
      repliesAttributes = new LinkedHashMap<>(replies);
    }
  }

  // ---- Convenience accessors ----

  public String getDefaultAiSystemPromptInstructions() {
    return prompt("DEFAULT_AI_SYSTEM_PROMPT_INSTRUCTIONS");
  }

  public String getDefaultAiReviewPromptDirectives() {
    return prompt("DEFAULT_AI_REVIEW_PROMPT_DIRECTIVES");
  }

  public String getDefaultAiPromptForceJsonFormat() {
    return prompt("DEFAULT_AI_PROMPT_FORCE_JSON_FORMAT");
  }

  public String getDefaultAiRepliesPromptSpecs() {
    return prompt("DEFAULT_AI_REPLIES_PROMPT_SPECS");
  }

  public String getDefaultAiRepliesPromptInline() {
    return prompt("DEFAULT_AI_REPLIES_PROMPT_INLINE");
  }

  public String getDefaultAiRepliesPromptEnforceResponseCheck() {
    return prompt("DEFAULT_AI_REPLIES_PROMPT_ENFORCE_RESPONSE_CHECK");
  }

  public String getDefaultAiRequestPromptDiff() {
    return prompt("DEFAULT_AI_REQUEST_PROMPT_DIFF");
  }

  public String getDefaultAiRequestPromptRequests() {
    return prompt("DEFAULT_AI_REQUEST_PROMPT_REQUESTS");
  }

  public String getDefaultAiReviewPromptCommitMessages() {
    return prompt("DEFAULT_AI_REVIEW_PROMPT_COMMIT_MESSAGES");
  }

  public String getDefaultAiReviewPromptInstructionsCommitMessages() {
    return prompt("DEFAULT_AI_REVIEW_PROMPT_INSTRUCTIONS_COMMIT_MESSAGES");
  }

  public String getDefaultAiRelevanceRules() {
    return prompt("DEFAULT_AI_RELEVANCE_RULES");
  }

  public String getDefaultAiHowToFindCommitMessage() {
    return prompt("DEFAULT_AI_HOW_TO_FIND_COMMIT_MESSAGE");
  }

  // ---- Static utility ----

  /** Reads a JSON prompt file and follows $extends chains. Returns merged key-value map. */
  public static Map<String, Object> getJsonPromptValues(String promptFilename) {
    String promptFile = String.format("config/%s.json", promptFilename);
    try (InputStreamReader reader = FileUtils.getInputStreamReader(promptFile)) {
      Map<String, Object> values =
          getGson().fromJson(reader, new TypeToken<Map<String, Object>>() {}.getType());
      Object parentPromptFilename = values.remove(PROMPT_EXTENDS_ATTRIBUTE);
      if (parentPromptFilename == null) {
        return values;
      }
      Map<String, Object> inheritedValues = getJsonPromptValues(parentPromptFilename.toString());
      inheritedValues.putAll(values);
      return inheritedValues;
    } catch (IOException e) {
      log.error("Failed to load prompts from file: {}", promptFilename, e);
      throw new RuntimeException("Failed to load prompts", e);
    }
  }

  // ---- Prompt building methods ----

  public String getReviewPromptCommitMessages() {
    log.debug("Constructing review prompt for commit messages.");
    return joinWithSpace(
        new ArrayList<>(
            List.of(
                String.format(
                    getDefaultAiReviewPromptCommitMessages(),
                    getDefaultAiHowToFindCommitMessage()),
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

  // ---- Backward-compatibility bridge for subclasses still calling loadDefaultPrompts ----

  protected void loadDefaultPrompts(String promptFilename) {
    loadPromptMap(promptFilename);
  }

  protected void loadDefaultPrompts(Class<?> promptClass, String promptFilename) {
    loadPromptMap(promptFilename);
  }
}
