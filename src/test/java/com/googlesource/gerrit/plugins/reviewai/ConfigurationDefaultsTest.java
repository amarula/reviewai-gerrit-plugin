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

package com.googlesource.gerrit.plugins.reviewai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

public class ConfigurationDefaultsTest {

  private static final String PLUGIN_NAME = "reviewai-gerrit-plugin";

  @Test
  public void shouldDefaultToOpenAiProviderAndModelWhenUnset() {
    Configuration configuration = createConfiguration();

    assertEquals(List.of("OpenAI"), configuration.getAiProviders());
    List<String> models = configuration.getAiModels();
    assertEquals(
        "OpenAI/" + Configuration.DEFAULT_OPENAI_AI_MODEL,
        models.getLast());
    assertEquals(models.getFirst(), "OpenAI/" + configuration.getAiModel());
    assertEquals(Configuration.OPENAI_DOMAIN, configuration.getAiDomain());
  }

  @Test
  public void shouldExposeModelsForConfiguredProviderRoutes() {
    Configuration configuration =
        createConfiguration(
            new String[] {"OpenAI", "LangChain/OpenAI", "LangChain/MoonShot"},
            new String[] {"OpenAI/gpt-4.1", "OpenAI/gpt-5.4", "MoonShot/moonshot-v1-8k"});

    assertEquals(
        List.of(
            "OpenAI/gpt-4.1",
            "OpenAI/gpt-5.4",
            "LangChain/OpenAI/gpt-4.1",
            "LangChain/OpenAI/gpt-5.4",
            "LangChain/MoonShot/moonshot-v1-8k"),
        configuration.getAiModels());
  }

  @Test
  public void shouldSelectFirstAiModelsEntryWhenDefaultIndexIsUnset() {
    Configuration configuration =
        createConfiguration(
            new String[] {"OpenAI"},
            new String[] {"OpenAI/gpt-4.1", "OpenAI/" + Configuration.DEFAULT_OPENAI_AI_MODEL});

    assertEquals("gpt-4.1", configuration.getAiModel());
    assertEquals("OpenAI/gpt-4.1", configuration.getSelectedAiModelRoute().modelRoute());
  }

  @Test
  public void shouldSelectConfiguredAiModelsDefaultIndex() {
    Configuration configuration =
        createConfiguration(
            new String[] {"OpenAI"},
            new String[] {"OpenAI/gpt-4.1", "OpenAI/" + Configuration.DEFAULT_OPENAI_AI_MODEL},
            2);

    assertEquals(Configuration.DEFAULT_OPENAI_AI_MODEL, configuration.getAiModel());
    assertEquals(
        "OpenAI/" + Configuration.DEFAULT_OPENAI_AI_MODEL,
        configuration.getSelectedAiModelRoute().modelRoute());
  }

  @Test
  public void shouldUseDefaultModelsForProviderWithoutConfiguredModels() {
    Configuration configuration =
        createConfiguration(new String[] {"MoonShot"}, new String[] {});

    assertEquals(
        "LangChain/MoonShot/" + Configuration.DEFAULT_MOONSHOT_AI_MODEL,
        configuration.getAiModels().getLast());
  }

  @Test
  public void shouldUseDefaultModelsForGeminiProviderWithoutConfiguredModels() {
    Configuration configuration =
        createConfiguration(new String[] {"Gemini"}, new String[] {});

    assertEquals(
        "LangChain/Gemini/" + Configuration.DEFAULT_GEMINI_AI_MODEL,
        configuration.getAiModels().getLast());
  }

  @Test
  public void shouldUseDefaultModelsForOllamaProviderWithoutConfiguredModels() {
    Configuration configuration =
        createConfiguration(new String[] {"Ollama"}, new String[] {});

    assertEquals(
        "LangChain/Ollama/" + Configuration.DEFAULT_OLLAMA_AI_MODEL,
        configuration.getAiModels().getLast());
  }

  @Test
  public void shouldExposeOllamaModelFromFullLangChainRoute() {
    Configuration configuration =
        createConfiguration(
            new String[] {"LangChain/Ollama"},
            new String[] {"LangChain/Ollama/llama3.2"});

    assertEquals(List.of("LangChain/Ollama"), configuration.getAiProviders());
    assertEquals(List.of("LangChain/Ollama/llama3.2"), configuration.getAiModels());
    assertEquals("llama3.2", configuration.getAiModel());
    assertEquals(Configuration.OLLAMA_DOMAIN, configuration.getAiDomain());
  }

  @Test
  public void shouldGuessOllamaRouteForBareLlamaModel() {
    Configuration configuration =
        createConfiguration(new String[] {}, new String[] {"llama3.2"});

    assertEquals(List.of("LangChain/Ollama"), configuration.getAiProviders());
    assertEquals(List.of("LangChain/Ollama/llama3.2"), configuration.getAiModels());
    assertEquals("llama3.2", configuration.getAiModel());
    assertEquals(Configuration.OLLAMA_DOMAIN, configuration.getAiDomain());
  }

  @Test
  public void shouldGuessOllamaRouteForBareModelWithoutToken() {
    Configuration configuration =
        createConfiguration(new String[] {}, new String[] {"custom-local-model"});

    assertEquals(List.of("LangChain/Ollama"), configuration.getAiProviders());
    assertEquals(List.of("LangChain/Ollama/custom-local-model"), configuration.getAiModels());
  }

  @Test
  public void shouldGuessOllamaRoutesForMultipleBareModelsWithoutTokens() throws Exception {
    Configuration configuration =
        createConfigurationFromResource("src/test/resources/__files/config/ollamaBareModels.config");

    assertEquals(List.of("LangChain/Ollama"), configuration.getAiProviders());
    assertEquals(
        List.of(
            "LangChain/Ollama/llama3.2",
            "LangChain/Ollama/deepseek-r1:1.5b",
            "LangChain/Ollama/gemini-3-flash-preview"),
        configuration.getAiModels());
  }

  @Test
  public void shouldGuessTokenProviderRouteForBareModelInDefaultProviderModels() {
    Configuration configuration =
        createConfiguration(
            new String[] {},
            new String[] {"gpt-4.1"},
            null,
            new String[] {"OpenAI/test-token"});

    assertEquals(List.of("OpenAI"), configuration.getAiProviders());
    assertEquals(List.of("OpenAI/gpt-4.1"), configuration.getAiModels());
  }

  @Test
  public void shouldGuessExplicitProviderRouteForBareModelWithCorrespondingToken() {
    Configuration configuration =
        createConfiguration(
            new String[] {"OpenAI"},
            new String[] {"gpt-4.1"},
            null,
            new String[] {"OpenAI/test-token"});

    assertEquals(List.of("OpenAI"), configuration.getAiProviders());
    assertEquals(List.of("OpenAI/gpt-4.1"), configuration.getAiModels());
  }

  @Test
  public void shouldGuessOllamaForBareModelNotInTokenBackedProviderModels() {
    Configuration configuration =
        createConfiguration(
            new String[] {"OpenAI", "LangChain/OpenAI", "LangChain/MoonShot", "LangChain/Ollama"},
            new String[] {"deepseek-r1:1.5b"},
            null,
            new String[] {"OpenAI/test-token", "MoonShot/test-token"});

    List<String> models = configuration.getAiModels();
    assertTrue(models.contains("LangChain/Ollama/deepseek-r1:1.5b"));
    assertTrue(!models.contains("OpenAI/deepseek-r1:1.5b"));
    assertTrue(!models.contains("LangChain/OpenAI/deepseek-r1:1.5b"));
    assertTrue(!models.contains("LangChain/MoonShot/deepseek-r1:1.5b"));
  }

  @Test
  public void shouldGuessProviderRouteForBareProviderNames() {
    Configuration configuration =
        createConfiguration(
            new String[] {"OpenAI", "MoonShot"},
            new String[] {"OpenAI/gpt-4.1", "MoonShot/moonshot-v1-8k"});

    assertEquals(List.of("OpenAI", "LangChain/MoonShot"), configuration.getAiProviders());
    assertEquals(
        List.of("OpenAI/gpt-4.1", "LangChain/MoonShot/moonshot-v1-8k"),
        configuration.getAiModels());
  }

  @Test
  public void shouldAppendMockAiModelWhenMockAddressIsConfigured() {
    Configuration configuration =
        createConfiguration(
            new String[] {"OpenAI", "MoonShot", "Ollama"},
            new String[] {"OpenAI/gpt-4.1", "MoonShot/moonshot-v1-8k", "Ollama/llama3.2"},
            null,
            new String[] {},
            "http://localhost:9090");

    List<String> models = configuration.getAiModels();
    assertEquals(
        List.of(
            "OpenAI/gpt-4.1",
            "LangChain/MoonShot/moonshot-v1-8k",
            "LangChain/Ollama/llama3.2",
            "OpenAI/mock-ai",
            "LangChain/MoonShot/mock-ai",
            "LangChain/Ollama/mock-ai"),
        models);
    assertTrue(models.contains("OpenAI/mock-ai"));
    assertTrue(models.contains("LangChain/MoonShot/mock-ai"));
    assertTrue(models.contains("LangChain/Ollama/mock-ai"));
  }

  @Test
  public void shouldSelectMockAiModelByConfiguredDefaultIndex() {
    Configuration configuration =
        createConfiguration(
            new String[] {"OpenAI"},
            new String[] {"OpenAI/gpt-4.1"},
            2,
            new String[] {"OpenAI/test-token"},
            "http://localhost:9090");

    assertEquals("mock-ai", configuration.getAiModel());
    assertEquals("OpenAI/mock-ai", configuration.getSelectedAiModelRoute().modelRoute());
    assertEquals("http://localhost:9090", configuration.getAiDomain());
    assertEquals("test-token", configuration.getAiToken());
  }

  @Test
  public void shouldUseMockAiAddressWhenSelectedMoonShotModelIsMockAi() {
    Config cfg = new Config();
    cfg.setStringList("plugin", PLUGIN_NAME, "aiProviders", List.of("MoonShot"));
    cfg.setString("plugin", PLUGIN_NAME, "mockAiAddress", "http://localhost:9090");
    cfg.setString("plugin", PLUGIN_NAME, "selectedAiModel", "MoonShot/mock-ai");
    Configuration configuration =
        createConfiguration(
            PluginConfig.createFromGerritConfig(PLUGIN_NAME, cfg), emptyPluginConfig());

    assertEquals(
        "LangChain/MoonShot/mock-ai", configuration.getSelectedAiModelRoute().modelRoute());
    assertEquals("mock-ai", configuration.getAiModel());
    assertEquals("LangChain", configuration.getAiProviderTransport().getConfigName());
    assertEquals("MoonShot", configuration.getAiProviderType().getConfigName());
    assertEquals("http://localhost:9090", configuration.getAiDomain());
  }

  @Test
  public void shouldDefaultNeutralReviewScoreConversionToEnabled() {
    Configuration configuration = createConfiguration();
    assertEquals(true, configuration.getConvertNeutralReviewScoreToPositive());
  }

  private Configuration createConfiguration() {
    return createConfiguration(new String[] {}, new String[] {});
  }

  private Configuration createConfiguration(String[] providers, String[] models) {
    return createConfiguration(providers, models, null);
  }

  private Configuration createConfiguration(
      String[] providers, String[] models, Integer defaultIndex) {
    return createConfiguration(providers, models, defaultIndex, new String[] {});
  }

  private Configuration createConfiguration(
      String[] providers, String[] models, Integer defaultIndex, String[] tokens) {
    return createConfiguration(providers, models, defaultIndex, tokens, null);
  }

  private Configuration createConfiguration(
      String[] providers,
      String[] models,
      Integer defaultIndex,
      String[] tokens,
      String mockAiAddress) {
    PluginConfig projectConfig = emptyPluginConfig();
    PluginConfig globalConfig = pluginConfig(providers, models, defaultIndex, tokens, mockAiAddress);

    return createConfiguration(globalConfig, projectConfig);
  }

  private Configuration createConfigurationFromResource(String resourcePath) throws Exception {
    Config cfg = new Config();
    cfg.fromText(Files.readString(Path.of(resourcePath), StandardCharsets.UTF_8));
    return createConfiguration(
        PluginConfig.createFromGerritConfig(PLUGIN_NAME, cfg), emptyPluginConfig());
  }

  private Configuration createConfiguration(PluginConfig globalConfig, PluginConfig projectConfig) {
    return new Configuration(
        (OneOffRequestContext) null,
        (GerritApi) null,
        globalConfig,
        projectConfig,
        ReviewTestBase.GERRIT_USER_ACCOUNT_EMAIL,
        Account.id(ReviewTestBase.GERRIT_USER_ACCOUNT_ID));
  }

  private PluginConfig pluginConfig(
      String[] providers, String[] models, Integer defaultIndex, String[] tokens) {
    return pluginConfig(providers, models, defaultIndex, tokens, null);
  }

  private PluginConfig pluginConfig(
      String[] providers,
      String[] models,
      Integer defaultIndex,
      String[] tokens,
      String mockAiAddress) {
    Config cfg = new Config();
    cfg.setStringList("plugin", PLUGIN_NAME, "aiProviders", List.of(providers));
    cfg.setStringList("plugin", PLUGIN_NAME, "aiModels", List.of(models));
    cfg.setStringList("plugin", PLUGIN_NAME, "aiTokens", List.of(tokens));
    if (defaultIndex != null) {
      cfg.setInt("plugin", PLUGIN_NAME, "aiModelsDefaultIndex", defaultIndex);
    }
    if (mockAiAddress != null) {
      cfg.setString("plugin", PLUGIN_NAME, "mockAiAddress", mockAiAddress);
    }
    return PluginConfig.createFromGerritConfig(PLUGIN_NAME, cfg);
  }

  private PluginConfig emptyPluginConfig() {
    return PluginConfig.createFromGerritConfig(PLUGIN_NAME, new Config());
  }
}
