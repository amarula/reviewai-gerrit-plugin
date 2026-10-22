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

package com.googlesource.gerrit.plugins.reviewai.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import com.google.gerrit.server.config.PluginConfig;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.eclipse.jgit.lib.Config;
import org.junit.Test;

public class AiAgentModelConfigurationTest {
  private static final String PLUGIN = "reviewai-gerrit-plugin";

  @Test
  public void defaultsToNoOverrides() {
    assertEquals(Map.of(), configuration(empty(), empty()).getAiAgentModels());
  }

  @Test
  public void defaultsToNoOverridesWhenConfigListsAreNull() {
    assertEquals(
        Map.of(),
        configuration(mock(PluginConfig.class), mock(PluginConfig.class)).getAiAgentModels());
  }

  @Test
  public void retainsGlobalAssignmentsWhenProjectConfigListIsNull() throws Exception {
    Configuration config =
        configuration(resource("agentModels.config", PLUGIN), mock(PluginConfig.class));

    assertEquals("Gemini/gemini-2.5-flash", config.getAiAgentModels().get(AiAgentRole.SECURITY));
  }

  @Test
  public void mergesProjectAssignmentsByRoleAndAllowsRequestInheritance() throws Exception {
    Configuration config =
        configuration(
            resource("agentModels.config", PLUGIN), resource("agentModelsProject.config", PLUGIN));

    Map<AiAgentRole, String> assignments = config.getAiAgentModels();

    assertEquals("REQUEST", assignments.get(AiAgentRole.SECURITY));
    assertEquals("Gemini/gemini-2.5-flash", assignments.get(AiAgentRole.CORRECTNESS));
    assertEquals("DeepSeek/deepseek-v4-flash", assignments.get(AiAgentRole.COMMIT_MESSAGE));
    assertEquals("DeepSeek/deepseek-v4-flash", config.getSelectedAiModelRoute().modelRoute());
    assertThrows(UnsupportedOperationException.class, assignments::clear);
  }

  @Test
  public void rejectsInvalidAssignmentsBeforeExecution() throws Exception {
    for (String section :
        new String[] {
          "unknownRole",
          "duplicateRole",
          "unknownModel",
          "invalidRoute",
          "emptyModel",
          "missingAssignment"
        }) {
      Configuration config =
          configuration(
              resource("agentModels.config", PLUGIN),
              resource("agentModelsInvalid.config", section));

      IllegalArgumentException failure =
          assertThrows(section, IllegalArgumentException.class, config::getAiAgentModels);

      assertTrue(failure.getMessage(), failure.getMessage().contains("aiAgentModels"));
    }
  }

  @Test
  public void alsoRejectsDuplicateGlobalAssignments() throws Exception {
    Configuration config =
        configuration(resource("agentModelsInvalid.config", "duplicateRole"), empty());

    assertTrue(
        assertThrows(IllegalArgumentException.class, config::getAiAgentModels)
            .getMessage()
            .contains("duplicate role in global"));
  }

  private Configuration configuration(PluginConfig global, PluginConfig project) {
    return new Configuration(null, null, global, project, "", null);
  }

  private PluginConfig empty() {
    return PluginConfig.createFromGerritConfig(PLUGIN, new Config());
  }

  private PluginConfig resource(String name, String section) throws Exception {
    Config config = new Config();
    config.fromText(read(name));
    return PluginConfig.createFromGerritConfig(section, config);
  }

  private String read(String name) throws Exception {
    try (InputStream input = getClass().getResourceAsStream("/__files/config/" + name)) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
