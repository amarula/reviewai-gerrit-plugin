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

import com.google.gerrit.entities.Account;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
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
            new String[] {"OpenAI/gpt-4.1", "OpenAI/gpt-5.4-mini", "MoonShot/moonshot-v1-8k"});

    assertEquals(
        List.of(
            "OpenAI/gpt-4.1",
            "OpenAI/gpt-5.4-mini",
            "LangChain/OpenAI/gpt-4.1",
            "LangChain/OpenAI/gpt-5.4-mini",
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
    PluginConfig projectConfig = emptyPluginConfig();
    PluginConfig globalConfig = pluginConfig(providers, models, defaultIndex);

    return new Configuration(
        (OneOffRequestContext) null,
        (GerritApi) null,
        globalConfig,
        projectConfig,
        ReviewTestBase.GERRIT_USER_ACCOUNT_EMAIL,
        Account.id(ReviewTestBase.GERRIT_USER_ACCOUNT_ID));
  }

  private PluginConfig pluginConfig(String[] providers, String[] models, Integer defaultIndex) {
    Config cfg = new Config();
    cfg.setStringList("plugin", PLUGIN_NAME, "aiProviders", List.of(providers));
    cfg.setStringList("plugin", PLUGIN_NAME, "aiModels", List.of(models));
    if (defaultIndex != null) {
      cfg.setInt("plugin", PLUGIN_NAME, "aiModelsDefaultIndex", defaultIndex);
    }
    return PluginConfig.createFromGerritConfig(PLUGIN_NAME, cfg);
  }

  private PluginConfig emptyPluginConfig() {
    return PluginConfig.createFromGerritConfig(PLUGIN_NAME, new Config());
  }
}
