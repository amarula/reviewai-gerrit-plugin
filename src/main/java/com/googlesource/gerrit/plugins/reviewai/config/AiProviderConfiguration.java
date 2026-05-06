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
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class AiProviderConfiguration {
  static final String OPENAI_DOMAIN = "https://api.openai.com";
  static final String GEMINI_DOMAIN = "https://generativelanguage.googleapis.com";
  static final String MOONSHOT_DOMAIN = "https://api.moonshot.ai";
  static final String OLLAMA_DOMAIN = "http://localhost:11434";
  static final String DEFAULT_OPENAI_AI_MODEL = "gpt-4.1";
  static final String DEFAULT_GEMINI_AI_MODEL = "gemini-2.5-flash";
  static final String DEFAULT_MOONSHOT_AI_MODEL = "moonshot-v1-8k";
  static final String DEFAULT_OLLAMA_AI_MODEL = "llama3.2";
  static final String MOCK_AI_MODEL = "mock-ai";
  static final String DEFAULT_OPENAI_ESTIMATOR_MODEL = "gpt-4o";
  static final String DEFAULT_GEMINI_ESTIMATOR_MODEL = "gemini-2.5-flash";
  static final String DEFAULT_MOONSHOT_ESTIMATOR_MODEL = "moonshot-v1-8k";
  static final String DEFAULT_OLLAMA_ESTIMATOR_MODEL = DEFAULT_OLLAMA_AI_MODEL;
  static final List<String> DEFAULT_OPENAI_AI_MODELS =
      List.of("gpt-5.4", "gpt-5.2", DEFAULT_OPENAI_AI_MODEL);
  static final List<String> DEFAULT_GEMINI_AI_MODELS =
      List.of("gemini-3.1-pro", "gemini-3.1-flash", "gemini-2.5-pro", DEFAULT_GEMINI_AI_MODEL);
  static final List<String> DEFAULT_MOONSHOT_AI_MODELS =
      List.of("kimi-k2.6", "kimi-k2.5", "kimi-k2-thinking", "kimi-k2-thinking-turbo", "kimi-k2-turbo-preview", DEFAULT_MOONSHOT_AI_MODEL);
  static final List<String> DEFAULT_OLLAMA_AI_MODELS = List.of(DEFAULT_OLLAMA_AI_MODEL);

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
    if (isMockAiModelRoute(getSelectedAiModelRoute())) {
      return getMockAiAddress();
    }
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
    List<String> configuredProviders = config.splitListIntoItems(KEY_AI_PROVIDER, List.of());
    List<String> configuredModels = config.splitListIntoItems(KEY_AI_MODELS, List.of());
    List<String> providers =
        configuredProviders.isEmpty() && !hasProviderRouteModel(configuredModels)
            ? DEFAULT_AI_PROVIDER
            : configuredProviders;
    Map<String, AiProviderRoute> providerRoutes = new LinkedHashMap<>();
    providers.stream()
        .map(this::parseProviderRoute)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .forEach(route -> providerRoutes.putIfAbsent(route.id(), route));
    getInferredProviderRoutes(configuredModels, configuredProviders.isEmpty())
        .forEach(route -> providerRoutes.putIfAbsent(route.id(), route));
    List<String> resolvedProviders = providerRoutes.keySet().stream().toList();
    log.debug(
        "AI provider routes resolved. configuredProviders={}, configuredModels={}, tokenProviders={}, resolvedProviders={}",
        configuredProviders,
        configuredModels,
        getAiTokenProviders(),
        resolvedProviders);
    return resolvedProviders;
  }

  List<String> getAiModels() {
    List<String> configuredModels = config.splitListIntoItems(KEY_AI_MODELS, List.of());
    List<AiProviderRoute> providerRoutes = getAiProviderRoutes();
    ConfiguredAiModels modelMap = getAiModelMap(configuredModels, providerRoutes);
    List<String> resolvedModels =
        providerRoutes.stream()
        .flatMap(
            providerRoute ->
                modelMap
                    .getModels(providerRoute)
                    .orElseGet(() -> getDefaultAiModels(providerRoute.provider()))
                    .stream()
                    .map(
                        model ->
                            new AiModelRoute(
                                providerRoute.transport(), providerRoute.provider(), model)))
        .map(AiModelRoute::modelRoute)
        .distinct()
        .toList();
    if (hasMockAiAddress()) {
      List<String> modelsWithMock = new ArrayList<>(resolvedModels);
      modelsWithMock.addAll(
          getMockAiModelRoutes(providerRoutes).stream().map(AiModelRoute::modelRoute).toList());
      resolvedModels = modelsWithMock.stream().distinct().toList();
    }
    log.debug(
        "AI model routes resolved. configuredModels={}, resolvedProviders={}, tokenProviders={}, resolvedModels={}",
        configuredModels,
        providerRoutes.stream().map(AiProviderRoute::id).toList(),
        getAiTokenProviders(),
        resolvedModels);
    return resolvedModels;
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
    log.warn("Ignoring invalid AI provider route `{}`", providerRoute);
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

  private ConfiguredAiModels getAiModelMap(
      List<String> configuredModels, List<AiProviderRoute> providerRoutes) {
    Map<AiProviderRoute, List<String>> routeModelMap = new LinkedHashMap<>();
    Map<AiProviderType, List<String>> providerModelMap = new LinkedHashMap<>();
    for (String configuredModelRoute : configuredModels) {
      String modelRoute = unwrapDumpQuotes(configuredModelRoute);
      if (modelRoute == null || modelRoute.isBlank()) {
        continue;
      }
      String[] parts = modelRoute.trim().split("/", 3);
      if (parts.length == 3) {
        Optional<AiProviderTransport> transport = AiProviderTransport.fromConfigName(parts[0]);
        Optional<AiProviderType> provider = AiProviderType.fromConfigName(parts[1]);
        if (transport.isPresent()
            && provider.isPresent()
            && supportsTransport(transport.get(), provider.get())) {
          AiProviderRoute providerRoute = new AiProviderRoute(transport.get(), provider.get());
          routeModelMap.computeIfAbsent(providerRoute, ignored -> new ArrayList<>()).add(parts[2]);
        } else {
          log.warn("Ignoring invalid AI model route `{}`", modelRoute);
        }
        continue;
      }
      if (parts.length == 2) {
        AiProviderType.fromConfigName(parts[0])
            .ifPresent(
                provider ->
                    providerModelMap
                        .computeIfAbsent(provider, ignored -> new ArrayList<>())
                        .add(parts[1]));
        continue;
      }
      guessProviderRoute(parts[0], providerRoutes, configuredModels)
          .ifPresent(
              providerRoute ->
                  routeModelMap
                      .computeIfAbsent(providerRoute, ignored -> new ArrayList<>())
                      .add(parts[0]));
    }
    return new ConfiguredAiModels(routeModelMap, providerModelMap);
  }

  private List<String> getDefaultAiModels(AiProviderType provider) {
    return switch (provider) {
      case GEMINI -> DEFAULT_GEMINI_AI_MODELS;
      case MOONSHOT -> DEFAULT_MOONSHOT_AI_MODELS;
      case OLLAMA -> DEFAULT_OLLAMA_AI_MODELS;
      case OPENAI -> DEFAULT_OPENAI_AI_MODELS;
    };
  }

  private String getDefaultAiDomain(AiProviderType provider) {
    return switch (provider) {
      case GEMINI -> GEMINI_DOMAIN;
      case MOONSHOT -> MOONSHOT_DOMAIN;
      case OLLAMA -> OLLAMA_DOMAIN;
      case OPENAI -> OPENAI_DOMAIN;
    };
  }

  private boolean hasProviderRouteModel(List<String> configuredModels) {
    return configuredModels.stream()
        .map(this::unwrapDumpQuotes)
        .anyMatch(
            modelRoute ->
                modelRoute != null
                    && !getInferredProviderRoutes(List.of(modelRoute), true).isEmpty());
  }

  private List<AiProviderRoute> getInferredProviderRoutes(
      List<String> configuredModels, boolean inferBareModels) {
    List<AiProviderRoute> providerRoutes = new ArrayList<>();
    for (String configuredModelRoute : configuredModels) {
      String modelRoute = unwrapDumpQuotes(configuredModelRoute);
      if (modelRoute == null || modelRoute.isBlank()) {
        continue;
      }
      String[] parts = modelRoute.trim().split("/", 3);
      if (parts.length == 3) {
        Optional<AiProviderTransport> transport = AiProviderTransport.fromConfigName(parts[0]);
        Optional<AiProviderType> provider = AiProviderType.fromConfigName(parts[1]);
        if (transport.isPresent()
            && provider.isPresent()
            && supportsTransport(transport.get(), provider.get())) {
          providerRoutes.add(new AiProviderRoute(transport.get(), provider.get()));
        } else {
          log.warn("Cannot infer provider from invalid AI model route `{}`", modelRoute);
        }
        continue;
      }
      if (parts.length == 2) {
        AiProviderType.fromConfigName(parts[0])
            .map(provider -> new AiProviderRoute(getDefaultTransport(provider), provider))
            .ifPresent(providerRoutes::add);
        continue;
      }
      if (inferBareModels) {
        guessBareModelProviderRoute(parts[0], providerRoutes, configuredModels)
            .ifPresent(providerRoutes::add);
      }
    }
    return providerRoutes;
  }

  private Optional<AiProviderRoute> guessProviderRoute(
      String model, List<AiProviderRoute> providerRoutes, List<String> configuredModels) {
    return guessBareModelProviderRoute(model, providerRoutes, configuredModels);
  }

  private Optional<AiProviderRoute> guessBareModelProviderRoute(
      String model, List<AiProviderRoute> providerRoutes, List<String> configuredModels) {
    if (model == null || model.isBlank()) {
      return Optional.empty();
    }
    List<AiProviderRoute> candidateRoutes =
        providerRoutes.isEmpty() ? getTokenProviderRoutes() : providerRoutes;
    List<AiProviderRoute> matchingTokenRoutes =
        candidateRoutes.stream()
            .filter(providerRoute -> hasAiToken(providerRoute.provider()))
            .filter(
                providerRoute ->
                    providerModelListContains(providerRoute.provider(), model, configuredModels))
            .toList();
    if (!matchingTokenRoutes.isEmpty()) {
      AiProviderRoute matchedRoute = matchingTokenRoutes.getFirst();
      if (matchingTokenRoutes.size() > 1) {
        log.debug(
            "AI model `{}` matches multiple token-backed provider routes {}. Using `{}`.",
            model,
            matchingTokenRoutes.stream().map(AiProviderRoute::id).toList(),
            matchedRoute.id());
      } else {
        log.debug(
            "AI model `{}` matched token-backed provider route `{}` by configured/default model list.",
            model,
            matchedRoute.id());
      }
      return Optional.of(matchedRoute);
    }

    Optional<AiProviderRoute> ollamaRoute = getOllamaProviderRoute(providerRoutes);
    if (ollamaRoute.isPresent()) {
      log.debug(
          "AI model `{}` is not present in configured/default model lists for token-backed providers {}. Guessing `{}`.",
          model,
          candidateRoutes.stream()
              .filter(providerRoute -> hasAiToken(providerRoute.provider()))
              .map(AiProviderRoute::id)
              .toList(),
          ollamaRoute.get().id());
      return ollamaRoute;
    }

    log.warn(
        "Cannot guess provider for AI model `{}`. It is not present in configured/default model lists for token-backed providers {}, and Ollama is not configured.",
        model,
        candidateRoutes.stream()
            .filter(providerRoute -> hasAiToken(providerRoute.provider()))
            .map(AiProviderRoute::id)
            .toList());
    return Optional.empty();
  }

  private boolean hasAiToken(AiProviderType provider) {
    String token = getAiTokens().get(provider.getConfigName());
    return token != null && !token.isBlank();
  }

  private List<AiProviderRoute> getTokenProviderRoutes() {
    return getAiTokens().keySet().stream()
        .map(AiProviderType::fromConfigName)
        .filter(Optional::isPresent)
        .map(Optional::get)
        .map(provider -> new AiProviderRoute(getDefaultTransport(provider), provider))
        .toList();
  }

  private boolean providerModelListContains(
      AiProviderType provider, String model, List<String> configuredModels) {
    return getConfiguredModelsForProvider(provider, configuredModels).contains(model)
        || getDefaultAiModels(provider).contains(model);
  }

  private List<String> getConfiguredModelsForProvider(
      AiProviderType targetProvider, List<String> configuredModels) {
    List<String> providerModels = new ArrayList<>();
    for (String configuredModelRoute : configuredModels) {
      String modelRoute = unwrapDumpQuotes(configuredModelRoute);
      if (modelRoute == null || modelRoute.isBlank()) {
        continue;
      }
      String[] parts = modelRoute.trim().split("/", 3);
      if (parts.length == 3) {
        AiProviderType.fromConfigName(parts[1])
            .filter(provider -> provider == targetProvider)
            .ifPresent(provider -> providerModels.add(parts[2]));
        continue;
      }
      if (parts.length == 2) {
        AiProviderType.fromConfigName(parts[0])
            .filter(provider -> provider == targetProvider)
            .ifPresent(provider -> providerModels.add(parts[1]));
      }
    }
    return providerModels;
  }

  private Optional<AiProviderRoute> getOllamaProviderRoute(List<AiProviderRoute> providerRoutes) {
    return providerRoutes.isEmpty()
        ? Optional.of(new AiProviderRoute(AiProviderTransport.LANGCHAIN, AiProviderType.OLLAMA))
        : providerRoutes.stream()
            .filter(providerRoute -> providerRoute.provider() == AiProviderType.OLLAMA)
            .findFirst();
  }

  private List<String> getAiTokenProviders() {
    return getAiTokens().keySet().stream().toList();
  }

  private boolean hasMockAiAddress() {
    return !getMockAiAddress().isBlank();
  }

  private String getMockAiAddress() {
    return config.getMockAiAddress() == null ? "" : config.getMockAiAddress().trim();
  }

  private List<AiModelRoute> getMockAiModelRoutes(List<AiProviderRoute> providerRoutes) {
    return providerRoutes.stream()
        .map(route -> new AiModelRoute(route.transport(), route.provider(), MOCK_AI_MODEL))
        .toList();
  }

  private boolean isMockAiModelRoute(AiModelRoute route) {
    return hasMockAiAddress() && route != null && MOCK_AI_MODEL.equals(route.model());
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

  private record ConfiguredAiModels(
      Map<AiProviderRoute, List<String>> routeModelMap,
      Map<AiProviderType, List<String>> providerModelMap) {
    private Optional<List<String>> getModels(AiProviderRoute providerRoute) {
      List<String> routeModels = routeModelMap.get(providerRoute);
      if (routeModels != null) {
        return Optional.of(routeModels);
      }
      return Optional.ofNullable(providerModelMap.get(providerRoute.provider()));
    }
  }
}
