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
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderTransport;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;

import java.util.*;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt.getJsonPromptValues;
import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;

public class Configuration extends ConfigCore {
  // Config Constants
  public static final String DEFAULT_EMPTY_SETTING = "";
  public static final String ENABLED_TOPICS_ALL = "ALL";

  // Default Config values
  public static final String OPENAI_DOMAIN = "https://api.openai.com";
  public static final String GEMINI_DOMAIN = "https://generativelanguage.googleapis.com";
  public static final String MOONSHOT_DOMAIN = "https://api.moonshot.ai";
  public static final String DEFAULT_AI_MODEL = "gpt-4o";
  public static final String DEFAULT_GEMINI_AI_MODEL = "gemini-2.5-flash";
  public static final String DEFAULT_MOONSHOT_AI_MODEL = "moonshot-v1-8k";
  public static final double DEFAULT_AI_REVIEW_TEMPERATURE = 0.2;
  public static final double DEFAULT_AI_COMMENT_TEMPERATURE = 1.0;

  private static final List<String> DEFAULT_AI_PROVIDER = List.of("OpenAI");
  private static final String KEY_AI_TOKENS = "aiTokens";
  private static final String KEY_AI_MODELS = "aiModels";
  private static final String KEY_AI_PROVIDER = "aiProvider";
  private static final boolean DEFAULT_REVIEW_PATCH_SET = true;
  private static final boolean DEFAULT_REVIEW_COMMIT_MESSAGES = true;
  private static final boolean DEFAULT_FULL_FILE_REVIEW = true;
  private static final String DEFAULT_CODE_CONTEXT_POLICY = "ON_DEMAND";
  private static final String DEFAULT_CODE_CONTEXT_ON_DEMAND_BASE_PATH = "";
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
  private static final boolean DEFAULT_TASK_SPECIFIC_ASSISTANTS = false;
  private static final int DEFAULT_AI_CONNECTION_TIMEOUT = 30;
  private static final int DEFAULT_AI_CONNECTION_MAX_RETRY_ATTEMPTS = 2;
  private static final int DEFAULT_AI_POLLING_TIMEOUT = 180;
  private static final int DEFAULT_AI_POLLING_INTERVAL = 1000;
  private static final int DEFAULT_AI_UPLOADED_CHUNK_SIZE_MB = 5;
  private static final int DEFAULT_AI_MAX_MEMORY_TOKENS = 16384;
  private static final boolean DEFAULT_ENABLE_MESSAGE_DEBUGGING = false;
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

  // Config entry keys with list values
  public static final Set<String> LIST_TYPE_ENTRY_KEYS =
      Set.of(
          KEY_DIRECTIVES,
          KEY_SELECTIVE_LOG_LEVEL_OVERRIDE,
          KEY_AI_PROVIDER,
          KEY_AI_MODELS,
          KEY_AI_TOKENS);

  private static final String SELECTED_AI_MODEL = "selectedAiModel";
  private static final String KEY_AI_DOMAIN = "aiDomain";
  private static final String KEY_REVIEW_COMMIT_MESSAGES = "aiReviewCommitMessages";
  private static final String KEY_REVIEW_PATCH_SET = "aiReviewPatchSet";
  private static final String KEY_FULL_FILE_REVIEW = "aiFullFileReview";
  private static final String KEY_CODE_CONTEXT_POLICY = "codeContextPolicy";
  private static final String KEY_CODE_CONTEXT_ON_DEMAND_BASE_PATH = "codeContextOnDemandBasePath";
  private static final String KEY_DISABLED_TOPIC_FILTER = "disabledTopicFilter";
  private static final String KEY_ENABLED_TOPIC_FILTER = "enabledTopicFilter";
  private static final String KEY_MAX_REVIEW_LINES = "maxReviewLines";
  private static final String KEY_ENABLED_FILE_EXTENSIONS = "enabledFileExtensions";
  private static final String KEY_ENABLED_VOTING = "enabledVoting";
  private static final String KEY_CONVERT_NEUTRAL_REVIEW_SCORE_TO_POSITIVE =
      "convertNeutralReviewScoreToPositive";
  private static final String KEY_FILTER_NEGATIVE_COMMENTS = "filterNegativeComments";
  private static final String KEY_FILTER_COMMENTS_BELOW_SCORE = "filterCommentsBelowScore";
  private static final String KEY_FILTER_RELEVANT_COMMENTS = "filterRelevantComments";
  private static final String KEY_FILTER_COMMENTS_RELEVANCE_THRESHOLD =
      "filterCommentsRelevanceThreshold";
  private static final String KEY_AI_MAX_MEMORY_TOKENS = "aiMaxMemoryTokens";
  private static final String KEY_INLINE_COMMENTS_AS_RESOLVED = "inlineCommentsAsResolved";
  private static final String KEY_PATCH_SET_COMMENTS_AS_RESOLVED = "patchSetCommentsAsResolved";
  private static final String KEY_IGNORE_OUTDATED_INLINE_COMMENTS = "ignoreOutdatedInlineComments";
  private static final String KEY_IGNORE_RESOLVED_AI_COMMENTS = "ignoreResolvedAiComments";
  private static final String KEY_TASK_SPECIFIC_ASSISTANTS = "taskSpecificAssistants";
  private static final String KEY_AI_CONNECTION_TIMEOUT = "aiConnectionTimeout";
  private static final String KEY_AI_CONNECTION_MAX_RETRY_ATTEMPTS = "aiConnectionMaxRetryAttempts";
  private static final String KEY_AI_POLLING_TIMEOUT = "aiPollingTimeout";
  private static final String KEY_AI_POLLING_INTERVAL = "aiPollingInterval";
  private static final String KEY_AI_UPLOADED_CHUNK_SIZE_MB = "aiUploadedChunkSizeMb";
  private static final String KEY_ENABLE_MESSAGE_DEBUGGING = "enableMessageDebugging";

  public Configuration(
      OneOffRequestContext context,
      GerritApi gerritApi,
      PluginConfig globalConfig,
      PluginConfig projectConfig,
      String gerritUserEmail,
      Account.Id userId) {
    super(context, gerritApi, globalConfig, projectConfig, gerritUserEmail, userId);
  }

  public String getAiToken() {
    return getAiToken(getSelectedAiModelRoute().provider());
  }

  public String getAiToken(AiProviderType provider) {
    String token = getAiTokens().get(provider.getConfigName());
    if (token == null || token.isBlank()) {
      throw new RuntimeException(String.format(NOT_CONFIGURED_ERROR_MSG, KEY_AI_TOKENS));
    }
    return token;
  }

  public String getGerritUserName() {
    return getValidatedOrThrow(KEY_GERRIT_USERNAME);
  }

  public String getAiDomain() {
    String aiDomain = getString(KEY_AI_DOMAIN);
    if (aiDomain != null && !aiDomain.isEmpty()) {
      return aiDomain;
    }

    return getDefaultAiDomain(getSelectedAiModelRoute().provider());
  }

  public String getAiModel() {
    return getSelectedAiModelRoute().model();
  }

  public List<String> getAiProvider() {
    List<String> providers = splitListIntoItems(KEY_AI_PROVIDER, DEFAULT_AI_PROVIDER);
    return providers.stream()
        .map(this::canonicalProviderRoute)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .distinct()
        .toList();
  }

  public List<String> getAiModels() {
    List<String> configuredModels = splitListIntoItems(KEY_AI_MODELS, List.of());
    Map<AiProviderType, List<String>> modelMap = getAiModelMap(configuredModels);
    return getAiProviderRoutes().stream()
        .flatMap(
            providerRoute ->
                modelMap
                    .getOrDefault(
                        providerRoute.provider(),
                        List.of(getDefaultAiModel(providerRoute.provider())))
                    .stream()
                    .map(
                        model ->
                            new AiModelRoute(
                                providerRoute.transport(), providerRoute.provider(), model)))
        .map(AiModelRoute::modelRoute)
        .distinct()
        .toList();
  }

  public Map<String, String> getAiTokens() {
    Map<String, String> tokens = new LinkedHashMap<>();
    for (String configuredTokenRoute : splitListIntoItems(KEY_AI_TOKENS, List.of())) {
      String tokenRoute = unwrapDumpQuotes(configuredTokenRoute);
      int separator = tokenRoute.indexOf("/");
      if (separator <= 0 || separator == tokenRoute.length() - 1) {
        continue;
      }
      AiProviderType.fromConfigName(tokenRoute.substring(0, separator))
          .ifPresent(
              provider ->
                  tokens.put(provider.getConfigName(), tokenRoute.substring(separator + 1)));
    }
    return tokens;
  }

  public AiModelRoute getSelectedAiModelRoute() {
    String selectedRoute = getString(SELECTED_AI_MODEL);
    if (!selectedRoute.isBlank()) {
      Optional<AiModelRoute> parsedRoute = AiModelRoute.parse(selectedRoute);
      if (parsedRoute.isPresent() && getAiModels().contains(parsedRoute.get().modelRoute())) {
        return parsedRoute.get();
      }
    }
    return getAiModels().stream()
        .findFirst()
        .flatMap(AiModelRoute::parse)
        .orElse(
            new AiModelRoute(AiProviderTransport.OPENAI, AiProviderType.OPENAI, DEFAULT_AI_MODEL));
  }

  public AiProviderType getAiProviderType() {
    return getSelectedAiModelRoute().provider();
  }

  public AiProviderTransport getAiProviderTransport() {
    return getSelectedAiModelRoute().transport();
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

  public String getCodeContextOnDemandBasePath() {
    return getString(
        KEY_CODE_CONTEXT_ON_DEMAND_BASE_PATH, DEFAULT_CODE_CONTEXT_ON_DEMAND_BASE_PATH);
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

  public boolean getTaskSpecificAssistants() {
    return getBoolean(KEY_TASK_SPECIFIC_ASSISTANTS, DEFAULT_TASK_SPECIFIC_ASSISTANTS);
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

  public int getAiPollingTimeout() {
    return getInt(KEY_AI_POLLING_TIMEOUT, DEFAULT_AI_POLLING_TIMEOUT);
  }

  public int getAiPollingInterval() {
    return getInt(KEY_AI_POLLING_INTERVAL, DEFAULT_AI_POLLING_INTERVAL);
  }

  public int getAiUploadedChunkSizeMb() {
    return getInt(KEY_AI_UPLOADED_CHUNK_SIZE_MB, DEFAULT_AI_UPLOADED_CHUNK_SIZE_MB);
  }

  public boolean getEnableMessageDebugging() {
    return getBoolean(KEY_ENABLE_MESSAGE_DEBUGGING, DEFAULT_ENABLE_MESSAGE_DEBUGGING);
  }

  public boolean getIgnoreOutdatedInlineComments() {
    return getBoolean(KEY_IGNORE_OUTDATED_INLINE_COMMENTS, DEFAULT_IGNORE_OUTDATED_INLINE_COMMENTS);
  }

  public List<String> getSelectiveLogLevelOverride() {
    return splitListIntoItems(
        KEY_SELECTIVE_LOG_LEVEL_OVERRIDE, DEFAULT_SELECTIVE_LOG_LEVEL_OVERRIDE);
  }

  private List<AiProviderRoute> getAiProviderRoutes() {
    return getAiProvider().stream()
        .map(this::parseProviderRoute)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toList();
  }

  private Optional<String> canonicalProviderRoute(String providerRoute) {
    return parseProviderRoute(providerRoute).map(AiProviderRoute::id);
  }

  private Optional<AiProviderRoute> parseProviderRoute(String providerRoute) {
    providerRoute = unwrapDumpQuotes(providerRoute);
    if (providerRoute == null || providerRoute.isBlank()) {
      return Optional.empty();
    }
    String[] parts = providerRoute.trim().split("/", 2);
    if (parts.length == 1) {
      return AiProviderType.fromConfigName(parts[0])
          .filter(provider -> provider == AiProviderType.OPENAI)
          .map(provider -> new AiProviderRoute(AiProviderTransport.OPENAI, provider));
    }

    Optional<AiProviderTransport> transport = AiProviderTransport.fromConfigName(parts[0]);
    Optional<AiProviderType> provider = AiProviderType.fromConfigName(parts[1]);
    if (transport.isPresent() && provider.isPresent()) {
      return Optional.of(new AiProviderRoute(transport.get(), provider.get()));
    }
    return Optional.empty();
  }

  private Map<AiProviderType, List<String>> getAiModelMap(List<String> configuredModels) {
    Map<AiProviderType, List<String>> modelMap = new LinkedHashMap<>();
    for (String configuredModelRoute : configuredModels) {
      String modelRoute = unwrapDumpQuotes(configuredModelRoute);
      int separator = modelRoute.indexOf("/");
      if (separator <= 0 || separator == modelRoute.length() - 1) {
        continue;
      }
      AiProviderType.fromConfigName(modelRoute.substring(0, separator))
          .ifPresent(
              provider ->
                  modelMap
                      .computeIfAbsent(provider, ignored -> new ArrayList<>())
                      .add(modelRoute.substring(separator + 1)));
    }
    return modelMap;
  }

  private String getDefaultAiModel(AiProviderType provider) {
    return switch (provider) {
      case GEMINI -> DEFAULT_GEMINI_AI_MODEL;
      case MOONSHOT -> DEFAULT_MOONSHOT_AI_MODEL;
      case OPENAI -> DEFAULT_AI_MODEL;
    };
  }

  private String getDefaultAiDomain(AiProviderType provider) {
    return switch (provider) {
      case GEMINI -> GEMINI_DOMAIN;
      case MOONSHOT -> MOONSHOT_DOMAIN;
      case OPENAI -> OPENAI_DOMAIN;
    };
  }

  private String unwrapDumpQuotes(String value) {
    if (value == null) {
      return null;
    }
    return value.replaceAll("^\"|\"$", "");
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public boolean isDefinedKey(String key) {
    return isDefinedKey(this.getClass(), key);
  }

  public TreeMap<String, String> dumpConfigMap() {
    return dumpConfigMap(this.getClass());
  }

  private record AiProviderRoute(AiProviderTransport transport, AiProviderType provider) {
    private String id() {
      if (transport == AiProviderTransport.OPENAI) {
        return provider.getConfigName();
      }
      return transport.getConfigName() + "/" + provider.getConfigName();
    }
  }
}
