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

package com.googlesource.gerrit.plugins.reviewai.config;

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt.getJsonPromptValues;
import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;

public class Configuration extends ConfigCore {
  // Config Constants
  public static final String DEFAULT_EMPTY_SETTING = "";
  public static final String ENABLED_TOPICS_ALL = "ALL";

  // Default Config values
  public static final String OPENAI_DOMAIN = AiProviderConfiguration.OPENAI_DOMAIN;
  public static final String GEMINI_DOMAIN = AiProviderConfiguration.GEMINI_DOMAIN;
  public static final String MOONSHOT_DOMAIN = AiProviderConfiguration.MOONSHOT_DOMAIN;
  public static final String OLLAMA_DOMAIN = AiProviderConfiguration.OLLAMA_DOMAIN;
  public static final String DEFAULT_OPENAI_AI_MODEL = AiProviderConfiguration.DEFAULT_OPENAI_AI_MODEL;
  public static final String DEFAULT_GEMINI_AI_MODEL = AiProviderConfiguration.DEFAULT_GEMINI_AI_MODEL;
  public static final String DEFAULT_MOONSHOT_AI_MODEL = AiProviderConfiguration.DEFAULT_MOONSHOT_AI_MODEL;
  public static final String DEFAULT_OLLAMA_AI_MODEL = AiProviderConfiguration.DEFAULT_OLLAMA_AI_MODEL;
  public static final String DEFAULT_OPENAI_ESTIMATOR_MODEL = AiProviderConfiguration.DEFAULT_OPENAI_ESTIMATOR_MODEL;
  public static final String DEFAULT_GEMINI_ESTIMATOR_MODEL = AiProviderConfiguration.DEFAULT_GEMINI_ESTIMATOR_MODEL;
  public static final String DEFAULT_MOONSHOT_ESTIMATOR_MODEL = AiProviderConfiguration.DEFAULT_MOONSHOT_ESTIMATOR_MODEL;
  public static final String DEFAULT_OLLAMA_ESTIMATOR_MODEL = AiProviderConfiguration.DEFAULT_OLLAMA_ESTIMATOR_MODEL;
  public static final double DEFAULT_AI_REVIEW_TEMPERATURE = 0.2;
  public static final double DEFAULT_AI_COMMENT_TEMPERATURE = 1.0;

  private static final String KEY_AI_TOKENS = AiProviderConfiguration.KEY_AI_TOKENS;
  private static final String KEY_AI_MODELS = AiProviderConfiguration.KEY_AI_MODELS;
  private static final String KEY_AI_PROVIDER = AiProviderConfiguration.KEY_AI_PROVIDER;
  private static final boolean DEFAULT_REVIEW_PATCH_SET = true;
  private static final boolean DEFAULT_REVIEW_COMMIT_MESSAGES = true;
  private static final boolean DEFAULT_FULL_FILE_REVIEW = true;
  private static final String DEFAULT_CODE_CONTEXT_POLICY = "ON_DEMAND";
  private static final String DEFAULT_DISABLED_TOPIC_FILTER = "";
  private static final String DEFAULT_ENABLED_TOPIC_FILTER = ENABLED_TOPICS_ALL;
  private static final String DEFAULT_ENABLED_FILE_EXTENSIONS =
      String.join(
          ",",
          new String[] {
            ".py", ".java", ".js", ".ts", ".html", ".css", ".cs", ".cpp", ".c", ".h", ".php", ".rb",
            ".swift", ".kt", ".r", ".jl", ".go", ".scala", ".pl", ".pm", ".rs", ".dart", ".lua",
            ".sh", ".vb", ".bat"
          });
  private static final List<String> DEFAULT_DIRECTIVES = new ArrayList<>();
  private static final int DEFAULT_MAX_REVIEW_LINES = 1000;
  private static final int DEFAULT_PATCH_CONTEXT_LINES = 3;
  private static final boolean DEFAULT_ENABLED_VOTING = false;
  private static final boolean DEFAULT_CONVERT_NEUTRAL_REVIEW_SCORE_TO_POSITIVE = true;
  private static final boolean DEFAULT_FILTER_NEGATIVE_COMMENTS = true;
  private static final int DEFAULT_FILTER_COMMENTS_BELOW_SCORE = 0;
  private static final boolean DEFAULT_FILTER_RELEVANT_COMMENTS = true;
  private static final double DEFAULT_FILTER_COMMENTS_RELEVANCE_THRESHOLD = 0.6;
  private static final int DEFAULT_VOTING_MIN_SCORE = -1;
  private static final int DEFAULT_VOTING_MAX_SCORE = 1;
  private static final boolean DEFAULT_INLINE_COMMENTS_AS_RESOLVED = false;
  private static final boolean DEFAULT_PATCH_SET_COMMENTS_AS_RESOLVED = false;
  private static final boolean DEFAULT_IGNORE_OUTDATED_INLINE_COMMENTS = false;
  private static final boolean DEFAULT_IGNORE_RESOLVED_AI_COMMENTS = true;
  private static final boolean DEFAULT_MULTI_AGENT_MODE = false;
  private static final int DEFAULT_AI_CONNECTION_TIMEOUT = 180;
  private static final int DEFAULT_AI_CONNECTION_MAX_RETRY_ATTEMPTS = 2;
  private static final int DEFAULT_AI_UPLOADED_CHUNK_SIZE_MB = 5;
  private static final int DEFAULT_AI_MAX_MEMORY_TOKENS = 16384;
  private static final int DEFAULT_AI_MAX_TOOL_RESPONSE_ROUNDS = 3;
  private static final int DEFAULT_OLLAMA_CONTEXT_WINDOW = 16384;
  private static final int DEFAULT_OLLAMA_RESPONSE_LENGTH = -1;
  private static final boolean DEFAULT_OLLAMA_THINK = false;
  private static final boolean DEFAULT_ENABLE_MESSAGE_DEBUGGING = false;
  private static final String DEFAULT_MOCK_AI_ADDRESS = DEFAULT_EMPTY_SETTING;
  private static final List<String> DEFAULT_SELECTIVE_LOG_LEVEL_OVERRIDE = new ArrayList<>();

  // Config setting keys
  public static final String KEY_AI_SYSTEM_PROMPT_INSTRUCTIONS = "aiSystemPromptInstructions";
  public static final String KEY_AI_RELEVANCE_RULES = "aiRelevanceRules";
  public static final String KEY_AI_REVIEW_TEMPERATURE = "aiReviewTemperature";
  public static final String KEY_AI_COMMENT_TEMPERATURE = "aiCommentTemperature";
  public static final String KEY_DIRECTIVES = "directive";
  public static final String KEY_VOTING_MIN_SCORE = "votingMinScore";
  public static final String KEY_VOTING_MAX_SCORE = "votingMaxScore";
  public static final String KEY_GERRIT_USERNAME = "gerritUserName";
  public static final String KEY_SELECTIVE_LOG_LEVEL_OVERRIDE = "selectiveLogLevelOverride";
  public static final String KEY_MOCK_AI_ADDRESS = "mockAiAddress";

  // Config entry keys with list values
  public static final Set<String> LIST_TYPE_ENTRY_KEYS =
      Set.of(
          KEY_DIRECTIVES,
          KEY_SELECTIVE_LOG_LEVEL_OVERRIDE,
          KEY_AI_PROVIDER,
          KEY_AI_MODELS,
          KEY_AI_TOKENS);

  private static final String KEY_AI_DOMAIN = AiProviderConfiguration.KEY_AI_DOMAIN;
  private static final String KEY_REVIEW_COMMIT_MESSAGES = "aiReviewCommitMessages";
  private static final String KEY_REVIEW_PATCH_SET = "aiReviewPatchSet";
  private static final String KEY_FULL_FILE_REVIEW = "aiFullFileReview";
  private static final String KEY_CODE_CONTEXT_POLICY = "codeContextPolicy";
  private static final String KEY_DISABLED_TOPIC_FILTER = "disabledTopicFilter";
  private static final String KEY_ENABLED_TOPIC_FILTER = "enabledTopicFilter";
  private static final String KEY_MAX_REVIEW_LINES = "maxReviewLines";
  private static final String KEY_PATCH_CONTEXT_LINES = "patchContextLines";
  private static final String KEY_ENABLED_FILE_EXTENSIONS = "enabledFileExtensions";
  private static final String KEY_ENABLED_VOTING = "enabledVoting";
  private static final String KEY_CONVERT_NEUTRAL_REVIEW_SCORE_TO_POSITIVE =
      "convertNeutralReviewScoreToPositive";
  private static final String KEY_FILTER_NEGATIVE_COMMENTS = "filterNegativeComments";
  private static final String KEY_FILTER_COMMENTS_BELOW_SCORE = "filterCommentsBelowScore";
  private static final String KEY_FILTER_RELEVANT_COMMENTS = "filterRelevantComments";
  private static final String KEY_FILTER_COMMENTS_RELEVANCE_THRESHOLD =
      "filterCommentsRelevanceThreshold";
  private static final String KEY_AI_MODELS_DEFAULT_INDEX =
      AiProviderConfiguration.KEY_AI_MODELS_DEFAULT_INDEX;
  private static final String KEY_AI_MAX_MEMORY_TOKENS = "aiMaxMemoryTokens";
  private static final String KEY_INLINE_COMMENTS_AS_RESOLVED = "inlineCommentsAsResolved";
  private static final String KEY_PATCH_SET_COMMENTS_AS_RESOLVED = "patchSetCommentsAsResolved";
  private static final String KEY_IGNORE_OUTDATED_INLINE_COMMENTS = "ignoreOutdatedInlineComments";
  private static final String KEY_IGNORE_RESOLVED_AI_COMMENTS = "ignoreResolvedAiComments";
  private static final String KEY_MULTI_AGENT_MODE = "multiAgentMode";
  private static final String KEY_AI_CONNECTION_TIMEOUT = "aiConnectionTimeout";
  private static final String KEY_AI_CONNECTION_MAX_RETRY_ATTEMPTS = "aiConnectionMaxRetryAttempts";
  private static final String KEY_AI_UPLOADED_CHUNK_SIZE_MB = "aiUploadedChunkSizeMb";
  private static final String KEY_AI_MAX_TOOL_RESPONSE_ROUNDS = "aiMaxToolResponseRounds";
  private static final String KEY_OLLAMA_CONTEXT_WINDOW = "ollamaContextWindow";
  private static final String KEY_OLLAMA_DOMAIN = "ollamaDomain";
  private static final String KEY_OLLAMA_RESPONSE_LENGTH = "ollamaResponseLength";
  private static final String KEY_OLLAMA_THINK = "ollamaThink";
  private static final String KEY_ENABLE_MESSAGE_DEBUGGING = "enableMessageDebugging";

  private final AiProviderConfiguration aiProviderConfiguration;

  public Configuration(
      OneOffRequestContext context,
      GerritApi gerritApi,
      PluginConfig globalConfig,
      PluginConfig projectConfig,
      String gerritUserEmail,
      Account.Id userId) {
    super(context, gerritApi, globalConfig, projectConfig, gerritUserEmail, userId);
    aiProviderConfiguration = new AiProviderConfiguration(this);
  }

  public String getAiToken() {
    return aiProviderConfiguration.getAiToken();
  }

  public String getGerritUserName() {
    return getValidatedOrThrow(KEY_GERRIT_USERNAME);
  }

  public String getAiDomain() {
    return aiProviderConfiguration.getAiDomain();
  }

  public String getAiModel() {
    return aiProviderConfiguration.getAiModel();
  }

  public List<String> getAiProviders() {
    return aiProviderConfiguration.getAiProviders();
  }

  public List<String> getAiModels() {
    return aiProviderConfiguration.getAiModels();
  }

  public int getAiModelsDefaultIndex() {
    return aiProviderConfiguration.getAiModelsDefaultIndex();
  }

  public AiModelRoute getSelectedAiModelRoute() {
    return aiProviderConfiguration.getSelectedAiModelRoute();
  }

  public AiProviderType getAiProviderType() {
    return aiProviderConfiguration.getAiProviderType();
  }

  // The default system prompt/instructions are specified in the prompt files and are passed as a
  // parameter
  public String getAiSystemPromptInstructions(String defaultAiSystemPromptInstructions) {
    return getString(KEY_AI_SYSTEM_PROMPT_INSTRUCTIONS, defaultAiSystemPromptInstructions);
  }

  public Optional<String> getConfiguredAiSystemPromptInstructions() {
    String value = getString(KEY_AI_SYSTEM_PROMPT_INSTRUCTIONS);
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
  }

  // If the default system prompt/instructions are not available in the caller's scope (e.g., when
  // displaying the configuration after a command request), they are retrieved from the prompt
  // files.
  public String getAiSystemPromptInstructions() {
    Map<String, Object> systemPrompts = getJsonPromptValues("prompts");
    return getAiSystemPromptInstructions(
        systemPrompts.get("DEFAULT_AI_SYSTEM_PROMPT_INSTRUCTIONS").toString());
  }

  public boolean getAiReviewPatchSet() {
    return getBoolean(KEY_REVIEW_PATCH_SET, DEFAULT_REVIEW_PATCH_SET);
  }

  public boolean getAiReviewCommitMessages() {
    return getBoolean(KEY_REVIEW_COMMIT_MESSAGES, DEFAULT_REVIEW_COMMIT_MESSAGES);
  }

  public boolean getAiFullFileReview() {
    return getBoolean(KEY_FULL_FILE_REVIEW, DEFAULT_FULL_FILE_REVIEW);
  }

  public CodeContextPolicies getCodeContextPolicy() {
    return getEnum(KEY_CODE_CONTEXT_POLICY, DEFAULT_CODE_CONTEXT_POLICY, CodeContextPolicies.class);
  }

  public List<String> getDisabledTopicFilter() {
    return splitConfig(getString(KEY_DISABLED_TOPIC_FILTER, DEFAULT_DISABLED_TOPIC_FILTER));
  }

  public List<String> getEnabledTopicFilter() {
    return splitConfig(getString(KEY_ENABLED_TOPIC_FILTER, DEFAULT_ENABLED_TOPIC_FILTER));
  }

  public int getMaxReviewLines() {
    return getInt(KEY_MAX_REVIEW_LINES, DEFAULT_MAX_REVIEW_LINES);
  }

  public int getPatchContextLines() {
    return Math.max(
        0,
        Integer.parseInt(
            getString(KEY_PATCH_CONTEXT_LINES, String.valueOf(DEFAULT_PATCH_CONTEXT_LINES))));
  }

  public List<String> getEnabledFileExtensions() {
    return splitConfigRemoveDots(
        getString(KEY_ENABLED_FILE_EXTENSIONS, DEFAULT_ENABLED_FILE_EXTENSIONS));
  }

  public List<String> getDirective() {
    return splitListIntoItems(KEY_DIRECTIVES, DEFAULT_DIRECTIVES);
  }

  public boolean isVotingEnabled() {
    return getBoolean(KEY_ENABLED_VOTING, DEFAULT_ENABLED_VOTING);
  }

  public boolean getConvertNeutralReviewScoreToPositive() {
    return getBoolean(
        KEY_CONVERT_NEUTRAL_REVIEW_SCORE_TO_POSITIVE,
        DEFAULT_CONVERT_NEUTRAL_REVIEW_SCORE_TO_POSITIVE);
  }

  public boolean getFilterNegativeComments() {
    return getBoolean(KEY_FILTER_NEGATIVE_COMMENTS, DEFAULT_FILTER_NEGATIVE_COMMENTS);
  }

  public int getFilterCommentsBelowScore() {
    return getInt(KEY_FILTER_COMMENTS_BELOW_SCORE, DEFAULT_FILTER_COMMENTS_BELOW_SCORE);
  }

  public boolean getFilterRelevantComments() {
    return getBoolean(KEY_FILTER_RELEVANT_COMMENTS, DEFAULT_FILTER_RELEVANT_COMMENTS);
  }

  public double getFilterCommentsRelevanceThreshold() {
    return getDouble(
        KEY_FILTER_COMMENTS_RELEVANCE_THRESHOLD, DEFAULT_FILTER_COMMENTS_RELEVANCE_THRESHOLD);
  }

  public String getAiRelevanceRules() {
    return getString(KEY_AI_RELEVANCE_RULES, DEFAULT_EMPTY_SETTING);
  }

  public String getAiReviewTemperature() {
    return getString(KEY_AI_REVIEW_TEMPERATURE, String.valueOf(DEFAULT_AI_REVIEW_TEMPERATURE));
  }

  public String getAiCommentTemperature() {
    return getString(KEY_AI_COMMENT_TEMPERATURE, String.valueOf(DEFAULT_AI_COMMENT_TEMPERATURE));
  }

  public int getVotingMinScore() {
    return getInt(KEY_VOTING_MIN_SCORE, DEFAULT_VOTING_MIN_SCORE);
  }

  public int getVotingMaxScore() {
    return getInt(KEY_VOTING_MAX_SCORE, DEFAULT_VOTING_MAX_SCORE);
  }

  public boolean getInlineCommentsAsResolved() {
    return getBoolean(KEY_INLINE_COMMENTS_AS_RESOLVED, DEFAULT_INLINE_COMMENTS_AS_RESOLVED);
  }

  public boolean getPatchSetCommentsAsResolved() {
    return getBoolean(KEY_PATCH_SET_COMMENTS_AS_RESOLVED, DEFAULT_PATCH_SET_COMMENTS_AS_RESOLVED);
  }

  public boolean getIgnoreResolvedAiComments() {
    return getBoolean(KEY_IGNORE_RESOLVED_AI_COMMENTS, DEFAULT_IGNORE_RESOLVED_AI_COMMENTS);
  }

  public boolean getMultiAgentMode() {
    return getBoolean(KEY_MULTI_AGENT_MODE, DEFAULT_MULTI_AGENT_MODE);
  }

  public int getAiConnectionTimeout() {
    return getInt(KEY_AI_CONNECTION_TIMEOUT, DEFAULT_AI_CONNECTION_TIMEOUT);
  }

  public int getAiMaxMemoryTokens() {
    return getInt(KEY_AI_MAX_MEMORY_TOKENS, DEFAULT_AI_MAX_MEMORY_TOKENS);
  }

  public int getAiConnectionMaxRetryAttempts() {
    return getInt(KEY_AI_CONNECTION_MAX_RETRY_ATTEMPTS, DEFAULT_AI_CONNECTION_MAX_RETRY_ATTEMPTS);
  }

  public int getAiUploadedChunkSizeMb() {
    return getInt(KEY_AI_UPLOADED_CHUNK_SIZE_MB, DEFAULT_AI_UPLOADED_CHUNK_SIZE_MB);
  }

  public int getAiMaxToolResponseRounds() {
    return Math.max(
        1, getInt(KEY_AI_MAX_TOOL_RESPONSE_ROUNDS, DEFAULT_AI_MAX_TOOL_RESPONSE_ROUNDS));
  }

  public int getOllamaContextWindow() {
    return Math.max(
        1, getIntAllowingProjectZero(KEY_OLLAMA_CONTEXT_WINDOW, DEFAULT_OLLAMA_CONTEXT_WINDOW));
  }

  public int getOllamaResponseLength() {
    return Math.max(
        -1, getIntAllowingProjectZero(KEY_OLLAMA_RESPONSE_LENGTH, DEFAULT_OLLAMA_RESPONSE_LENGTH));
  }

  public String getOllamaDomain() {
    String ollamaDomain = getString(KEY_OLLAMA_DOMAIN);
    if (ollamaDomain != null && !ollamaDomain.isBlank()) {
      return ollamaDomain;
    }
    return OLLAMA_DOMAIN;
  }

  public boolean getOllamaThink() {
    return getBoolean(KEY_OLLAMA_THINK, DEFAULT_OLLAMA_THINK);
  }

  public boolean getEnableMessageDebugging() {
    return getBoolean(KEY_ENABLE_MESSAGE_DEBUGGING, DEFAULT_ENABLE_MESSAGE_DEBUGGING);
  }

  public String getMockAiAddress() {
    return getString(KEY_MOCK_AI_ADDRESS, DEFAULT_MOCK_AI_ADDRESS);
  }

  public boolean getIgnoreOutdatedInlineComments() {
    return getBoolean(KEY_IGNORE_OUTDATED_INLINE_COMMENTS, DEFAULT_IGNORE_OUTDATED_INLINE_COMMENTS);
  }

  public List<String> getSelectiveLogLevelOverride() {
    return splitListIntoItems(
        KEY_SELECTIVE_LOG_LEVEL_OVERRIDE, DEFAULT_SELECTIVE_LOG_LEVEL_OVERRIDE);
  }

  public boolean isDefinedKey(String key) {
    return isDefinedKey(this.getClass(), key);
  }

  public Optional<List<String>> getValidDynamicConfigValues(String key) {
    if (KEY_CODE_CONTEXT_POLICY.equals(key)) {
      return Optional.of(Arrays.stream(CodeContextPolicies.values()).map(Enum::name).toList());
    }
    return Optional.empty();
  }

  public TreeMap<String, String> dumpConfigMap() {
    return dumpConfigMap(this.getClass());
  }

  private int getIntAllowingProjectZero(String key, int defaultValue) {
    if (projectConfig.getString(key) != null) {
      return projectConfig.getInt(key, defaultValue);
    }
    return globalConfig.getInt(key, defaultValue);
  }
}
