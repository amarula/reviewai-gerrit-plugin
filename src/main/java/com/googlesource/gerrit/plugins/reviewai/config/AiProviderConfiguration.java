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

import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderTransport;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class AiProviderConfiguration {
  static final String OPENAI_DOMAIN = "https://api.openai.com";
  static final String GEMINI_DOMAIN = "https://generativelanguage.googleapis.com";
  static final String MOONSHOT_DOMAIN = "https://api.moonshot.ai";
  static final String DEFAULT_OPENAI_AI_MODEL = "gpt-4.1";
  static final String DEFAULT_GEMINI_AI_MODEL = "gemini-2.5-flash";
  static final String DEFAULT_MOONSHOT_AI_MODEL = "moonshot-v1-8k";
  static final String DEFAULT_OPENAI_ESTIMATOR_MODEL = "gpt-4o";
  static final String DEFAULT_GEMINI_ESTIMATOR_MODEL = "gemini-2.5-flash";
  static final String DEFAULT_MOONSHOT_ESTIMATOR_MODEL = "moonshot-v1-8k";
  static final List<String> DEFAULT_OPENAI_AI_MODELS =
      List.of("gpt-5.4", "gpt-5.2", DEFAULT_OPENAI_AI_MODEL);
  static final List<String> DEFAULT_GEMINI_AI_MODELS =
      List.of("gemini-3.1-pro", "gemini-3.1-flash", "gemini-2.5-pro", DEFAULT_GEMINI_AI_MODEL);
  static final List<String> DEFAULT_MOONSHOT_AI_MODELS =
      List.of("kimi-k2.6", "kimi-k2.5", "kimi-k2-thinking", "kimi-k2-thinking-turbo", "kimi-k2-turbo-preview", DEFAULT_MOONSHOT_AI_MODEL);

  static final String KEY_AI_TOKENS = "aiTokens";
  static final String KEY_AI_MODELS = "aiModels";
  static final String KEY_AI_PROVIDER = "aiProviders";
  static final String KEY_AI_DOMAIN = "aiDomain";
  static final String KEY_AI_MODELS_DEFAULT_INDEX = "aiModelsDefaultIndex";

  private static final int DEFAULT_AI_MODELS_DEFAULT_INDEX = 1;

  private static final List<String> DEFAULT_AI_PROVIDER = List.of("OpenAI");
  private static final String SELECTED_AI_MODEL = "selectedAiModel";

  private final Configuration config;

  AiProviderConfiguration(Configuration config) {
    this.config = config;
  }

  String getAiToken() {
    return getAiToken(getSelectedAiModelRoute().provider());
  }

  String getAiToken(AiProviderType provider) {
    String token = getAiTokens().get(provider.getConfigName());
    if (token == null || token.isBlank()) {
      throw new RuntimeException(
          String.format(ConfigCore.NOT_CONFIGURED_ERROR_MSG, KEY_AI_TOKENS));
    }
    return token;
  }

  String getAiDomain() {
    String aiDomain = config.getString(KEY_AI_DOMAIN);
    if (aiDomain != null && !aiDomain.isEmpty()) {
      return aiDomain;
    }

    return getDefaultAiDomain(getSelectedAiModelRoute().provider());
  }

  String getAiModel() {
    return getSelectedAiModelRoute().model();
  }

  List<String> getAiProviders() {
    List<String> providers = config.splitListIntoItems(KEY_AI_PROVIDER, DEFAULT_AI_PROVIDER);
    return providers.stream()
        .map(this::canonicalProviderRoute)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .distinct()
        .toList();
  }

  List<String> getAiModels() {
    List<String> configuredModels = config.splitListIntoItems(KEY_AI_MODELS, List.of());
    Map<AiProviderType, List<String>> modelMap = getAiModelMap(configuredModels);
    return getAiProviderRoutes().stream()
        .flatMap(
            providerRoute ->
                modelMap
                    .getOrDefault(
                        providerRoute.provider(),
                        getDefaultAiModels(providerRoute.provider()))
                    .stream()
                    .map(
                        model ->
                            new AiModelRoute(
                                providerRoute.transport(), providerRoute.provider(), model)))
        .map(AiModelRoute::modelRoute)
        .distinct()
        .toList();
  }

  Map<String, String> getAiTokens() {
    Map<String, String> tokens = new LinkedHashMap<>();
    for (String configuredTokenRoute : config.splitListIntoItems(KEY_AI_TOKENS, List.of())) {
      String tokenRoute = unwrapDumpQuotes(configuredTokenRoute);
      int separator = tokenRoute.indexOf("/");
      if (separator <= 0 || separator == tokenRoute.length() - 1) {
        continue;
      }
      AiProviderType.fromConfigName(tokenRoute.substring(0, separator))
          .ifPresent(
              provider -> tokens.put(provider.getConfigName(), tokenRoute.substring(separator + 1)));
    }
    return tokens;
  }

  int getAiModelsDefaultIndex() {
    return config.getInt(KEY_AI_MODELS_DEFAULT_INDEX, DEFAULT_AI_MODELS_DEFAULT_INDEX);
  }

  AiModelRoute getSelectedAiModelRoute() {
    String selectedRoute = config.getString(SELECTED_AI_MODEL);
    if (!selectedRoute.isBlank()) {
      Optional<AiModelRoute> parsedRoute = AiModelRoute.parse(selectedRoute);
      if (parsedRoute.isPresent() && getAiModels().contains(parsedRoute.get().modelRoute())) {
        return parsedRoute.get();
      }
    }
    return getDefaultAiModelRoute()
        .orElse(
            new AiModelRoute(AiProviderTransport.OPENAI, AiProviderType.OPENAI, DEFAULT_OPENAI_AI_MODEL));
  }

  AiProviderType getAiProviderType() {
    return getSelectedAiModelRoute().provider();
  }

  AiProviderTransport getAiProviderTransport() {
    return getSelectedAiModelRoute().transport();
  }

  private List<AiProviderRoute> getAiProviderRoutes() {
    return getAiProviders().stream()
        .map(this::parseProviderRoute)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .toList();
  }

  private Optional<String> canonicalProviderRoute(String providerRoute) {
    return parseProviderRoute(providerRoute).map(AiProviderRoute::id);
  }

  private Optional<AiModelRoute> getDefaultAiModelRoute() {
    List<String> models = getAiModels();
    int zeroBasedIndex = getAiModelsDefaultIndex() - 1;
    if (zeroBasedIndex >= 0 && zeroBasedIndex < models.size()) {
      return AiModelRoute.parse(models.get(zeroBasedIndex));
    }
    return models.stream().findFirst().flatMap(AiModelRoute::parse);
  }

  private Optional<AiProviderRoute> parseProviderRoute(String providerRoute) {
    providerRoute = unwrapDumpQuotes(providerRoute);
    if (providerRoute == null || providerRoute.isBlank()) {
      return Optional.empty();
    }
    String[] parts = providerRoute.trim().split("/", 2);
    if (parts.length == 1) {
      return AiProviderType.fromConfigName(parts[0])
          .map(provider -> new AiProviderRoute(getDefaultTransport(provider), provider));
    }

    Optional<AiProviderTransport> transport = AiProviderTransport.fromConfigName(parts[0]);
    Optional<AiProviderType> provider = AiProviderType.fromConfigName(parts[1]);
    if (transport.isPresent()
        && provider.isPresent()
        && supportsTransport(transport.get(), provider.get())) {
      return Optional.of(new AiProviderRoute(transport.get(), provider.get()));
    }
    return Optional.empty();
  }

  private AiProviderTransport getDefaultTransport(AiProviderType provider) {
    if (provider.supportsDirectConnection()) {
      return AiProviderTransport.OPENAI;
    }
    return AiProviderTransport.LANGCHAIN;
  }

  private boolean supportsTransport(AiProviderTransport transport, AiProviderType provider) {
    return transport != AiProviderTransport.OPENAI || provider.supportsDirectConnection();
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

  private List<String> getDefaultAiModels(AiProviderType provider) {
    return switch (provider) {
      case GEMINI -> DEFAULT_GEMINI_AI_MODELS;
      case MOONSHOT -> DEFAULT_MOONSHOT_AI_MODELS;
      case OPENAI -> DEFAULT_OPENAI_AI_MODELS;
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

  private record AiProviderRoute(AiProviderTransport transport, AiProviderType provider) {
    private String id() {
      if (transport == AiProviderTransport.OPENAI && provider.supportsDirectConnection()) {
        return provider.getConfigName();
      }
      return transport.getConfigName() + "/" + provider.getConfigName();
    }
  }
}
