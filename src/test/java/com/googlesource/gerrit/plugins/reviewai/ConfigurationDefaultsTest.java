/*
 * Copyright (c) 2025. The Android Open Source Project
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
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.gerrit.extensions.api.GerritApi;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings.AiBackends;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings.LangChainProviders;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

public class ConfigurationDefaultsTest {

  private static final String PLUGIN_NAME = "reviewai-gerrit-plugin";

  @Test
  public void shouldDefaultToLangChainGeminiModelWhenUnset() {
    Configuration configuration = createConfiguration(AiBackends.LANGCHAIN, LangChainProviders.GEMINI);
    assertEquals(Configuration.DEFAULT_GEMINI_AI_MODEL, configuration.getAiModel());
  }

  @Test
  public void shouldDefaultToLangChainMoonshotModelWhenUnset() {
    Configuration configuration = createConfiguration(AiBackends.LANGCHAIN, LangChainProviders.MOONSHOT);
    assertEquals(Configuration.DEFAULT_MOONSHOT_AI_MODEL, configuration.getAiModel());
  }

  @Test
  public void shouldDefaultToLangChainOpenAiModelWhenUnset() {
    Configuration configuration = createConfiguration(AiBackends.LANGCHAIN, LangChainProviders.OPENAI);
    assertEquals(Configuration.DEFAULT_AI_MODEL, configuration.getAiModel());
  }

  @Test
  public void shouldDefaultToOpenAiModelForNonLangChainBackend() {
    Configuration configuration = createConfiguration(AiBackends.OPENAI, LangChainProviders.OPENAI);
    assertEquals(Configuration.DEFAULT_AI_MODEL, configuration.getAiModel());
  }

  @Test
  public void shouldDefaultToLangChainGeminiDomainWhenUnset() {
    Configuration configuration = createConfiguration(AiBackends.LANGCHAIN, LangChainProviders.GEMINI);
    assertEquals(Configuration.GEMINI_DOMAIN, configuration.getAiDomain());
  }

  @Test
  public void shouldDefaultToLangChainMoonshotDomainWhenUnset() {
    Configuration configuration = createConfiguration(AiBackends.LANGCHAIN, LangChainProviders.MOONSHOT);
    assertEquals(Configuration.MOONSHOT_DOMAIN, configuration.getAiDomain());
  }

  @Test
  public void shouldDefaultToLangChainOpenAiDomainWhenUnset() {
    Configuration configuration = createConfiguration(AiBackends.LANGCHAIN, LangChainProviders.OPENAI);
    assertEquals(Configuration.OPENAI_DOMAIN, configuration.getAiDomain());
  }

  @Test
  public void shouldDefaultToOpenAiDomainForNonLangChainBackend() {
    Configuration configuration = createConfiguration(AiBackends.OPENAI, LangChainProviders.OPENAI);
    assertEquals(Configuration.OPENAI_DOMAIN, configuration.getAiDomain());
  }

  private Configuration createConfiguration(AiBackends backend, LangChainProviders provider) {
    PluginConfig projectConfig = emptyPluginConfig();
    PluginConfig globalConfig = pluginConfigWithBackend(backend, provider);

    return new Configuration(
        (OneOffRequestContext) null,
        (GerritApi) null,
        globalConfig,
        projectConfig,
        ReviewTestBase.GERRIT_USER_ACCOUNT_EMAIL,
        Account.id(ReviewTestBase.GERRIT_USER_ACCOUNT_ID));
  }

  private PluginConfig pluginConfigWithBackend(AiBackends backend, LangChainProviders provider) {
    Config cfg = new Config();
    cfg.setString("plugin", PLUGIN_NAME, "aiBackend", backend.name());
    cfg.setString("plugin", PLUGIN_NAME, "lcProvider", provider.name());
    return PluginConfig.createFromGerritConfig(PLUGIN_NAME, cfg);
  }

  private PluginConfig emptyPluginConfig() {
    return PluginConfig.createFromGerritConfig(PLUGIN_NAME, new Config());
  }
}
