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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptFactory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.patchset.AiPromptReviewCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class AiPromptReviewSkillTest {
  private static final String GUIDELINES_RESOURCE = "__files/skills/reviewGuidelines.txt";
  private static final String ROLE_RESOURCE = "__files/langchain/chatMemoryUserMessage.txt";

  @Test
  public void singleAgentPromptIncludesSkillAndPreservesConfiguredRole() throws Exception {
    Configuration config = configuration();
    String role = readResource(ROLE_RESOURCE).strip();
    when(config.getConfiguredAiSystemPromptInstructions()).thenReturn(Optional.of(role));

    try (var uiPrompts = mockStatic(GerritUiPromptLoader.class)) {
      var prompt =
          AiPromptFactory.getAiPrompt(
              config,
              new ChangeSetData(1),
              mock(GerritChange.class),
              mock(ICodeContextPolicy.class));

      assertEquals(AiPromptReview.class, prompt.getClass());
      String instructions = prompt.getDefaultAiAssistantInstructions();
      assertTrue(instructions.contains("# Role\n\n" + role));
      assertTrue(instructions.contains(readResource(GUIDELINES_RESOURCE).strip()));
      assertTrue(instructions.contains("# MANDATORY Response Format"));
      assertTrue(instructions.contains("\"replies\""));
      assertFalse(instructions.contains("description:"));
      uiPrompts.verifyNoInteractions();

      List<String> additionalInstructions = new ArrayList<>();
      prompt.addAiAssistantInstructions(additionalInstructions);
      assertTrue(
          String.join("\n", additionalInstructions)
              .contains(readResource(GUIDELINES_RESOURCE).strip()));
    }
  }

  @Test
  public void skillPreservesGerritUiRoleWhenNoRoleIsConfigured() throws Exception {
    Configuration config = configuration();
    String role = readResource(ROLE_RESOURCE).strip();
    try (var uiPrompts = mockStatic(GerritUiPromptLoader.class)) {
      uiPrompts
          .when(() -> GerritUiPromptLoader.resolveReviewInstructions(anyString()))
          .thenReturn(role);
      var prompt =
          AiPromptFactory.getAiPrompt(
              config,
              new ChangeSetData(1),
              mock(GerritChange.class),
              mock(ICodeContextPolicy.class));

      String instructions = prompt.getDefaultAiAssistantInstructions();
      assertTrue(instructions.contains("# Role\n\n" + role));
      assertTrue(instructions.contains(readResource(GUIDELINES_RESOURCE).strip()));
    }
  }

  @Test
  public void scopedReviewUsesSharedSkillGuidelines() throws Exception {
    Configuration config = configuration();
    when(config.getAgentSpecializationLevel()).thenReturn(AgentSpecializationLevel.SCOPED_AGENTS);
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    when(config.getMultiAgentMode()).thenReturn(true);
    when(config.getConfiguredAiSystemPromptInstructions())
        .thenReturn(Optional.of(readResource(ROLE_RESOURCE).strip()));
    ChangeSetData data = new ChangeSetData(1);
    data.setReviewAssistantStage(ReviewAssistantStage.REVIEW_CODE);

    var prompt =
        AiPromptFactory.getAiPrompt(
            config, data, mock(GerritChange.class), mock(ICodeContextPolicy.class));

    assertEquals(AiPromptReviewCode.class, prompt.getClass());
    assertTrue(
        prompt
            .getDefaultAiAssistantInstructions()
            .contains(readResource(GUIDELINES_RESOURCE).strip()));
    List<String> additionalInstructions = new ArrayList<>();
    prompt.addAiAssistantInstructions(additionalInstructions);
    assertTrue(
        String.join("\n", additionalInstructions)
            .contains(readResource(GUIDELINES_RESOURCE).strip()));
  }

  private Configuration configuration() {
    Configuration config = mock(Configuration.class);
    when(config.getAgentSpecializationLevel()).thenReturn(AgentSpecializationLevel.SINGLE_AGENT);
    return config;
  }

  private String readResource(String name) throws Exception {
    try (InputStream resource = getClass().getClassLoader().getResourceAsStream(name)) {
      assertNotNull(name, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
