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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewCollector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewAgent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewTriage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinitions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainSuggestClient;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class LangChainSpecializedAgentReviewClientTest {
  private static final Path TEST_RESOURCES_PATH = Paths.get("src/test/resources");
  private static final String PATCH_SET_RESOURCE = "__files/langchain/suggestOriginalPatchSet.txt";
  private static final String TRIAGE_RESPONSE_RESOURCE =
      "__files/langchain/specializedTriageResponse.json";
  private static final String WRAPPED_TRIAGE_RESPONSE_RESOURCE =
      "__files/langchain/specializedTriageWrappedResponse.json";
  private static final String SELECTED_PATCHSET_CONTEXT_RESOURCE =
      "__files/langchain/specializedSelectedPatchsetContext.txt";

  @Test
  public void reviewRunsEnabledSpecializedAgentsAndCollector() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    client.triage =
        triage(
            plan("CORRECTNESS", true),
            plan("SECURITY", false));
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    GerritChange change = change(false);

    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(PATCH_SET_RESOURCE));

    assertNotNull(response.getReplies());
    assertEquals(List.of("CORRECTNESS"), client.recordedAgents);
    assertEquals(List.of("CORRECTNESS"), client.collectorAgents);
    assertEquals("Collected review", response.getReplies().getFirst().getReply());
  }

  @Test
  public void commitMessageScopeRunsOnlyCommitMessageSpecialist() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    client.triage =
        triage(
            plan("COMMIT_MESSAGE", true),
            plan("CORRECTNESS", true));
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setReviewScope(ReviewScope.COMMIT_MESSAGE);
    GerritChange change = change(false);

    client.ask(changeSetData, change, readTestResource(PATCH_SET_RESOURCE));

    assertEquals(List.of("COMMIT_MESSAGE"), client.recordedAgents);
  }

  @Test
  public void suggestModeUsesSuggestClientWithoutRunningSpecializedReview() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    client.triage = triage(plan("CORRECTNESS", true));
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    GerritChange change = change(true);

    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(PATCH_SET_RESOURCE));

    assertTrue(client.suggestClientCalled);
    assertFalse(client.triageCalled);
    assertEquals("suggestion", response.getReplies().getFirst().getReply());
  }

  @Test
  public void parsesDirectTriageResponse() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());

    SpecializedReviewTriage triage =
        client.parseTriageResponse(readTestResource(TRIAGE_RESPONSE_RESOURCE));

    assertEquals(2, triage.getAgents().size());
    assertEquals("CORRECTNESS", triage.getAgents().getFirst().getAgent());
    assertTrue(triage.getAgents().getFirst().isEnabled());
    assertEquals("SECURITY", triage.getAgents().get(1).getAgent());
    assertFalse(triage.getAgents().get(1).isEnabled());
  }

  @Test
  public void parsesTriageResponseWrappedInReviewReply() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());

    SpecializedReviewTriage triage =
        client.parseTriageResponse(readTestResource(WRAPPED_TRIAGE_RESPONSE_RESOURCE));

    assertEquals(3, triage.getAgents().size());
    assertEquals("COMMIT_MESSAGE", triage.getAgents().getFirst().getAgent());
    assertTrue(triage.getAgents().getFirst().isEnabled());
    assertEquals("CORRECTNESS", triage.getAgents().get(1).getAgent());
    assertTrue(triage.getAgents().get(1).isEnabled());
    assertEquals("SECURITY", triage.getAgents().get(2).getAgent());
    assertFalse(triage.getAgents().get(2).isEnabled());
  }

  @Test
  public void specializedAgentDefinitionsAreLoadedFromJsonFiles() {
    List<String> agentNames =
        SpecializedReviewAgentDefinitions.load().stream()
            .map(definition -> definition.normalizedName())
            .toList();

    assertTrue(agentNames.contains("CORRECTNESS"));
    assertTrue(agentNames.contains("TESTABILITY"));
    assertTrue(agentNames.contains("CODE_QUALITY"));
    assertTrue(agentNames.contains("DOCUMENTATION"));
    assertTrue(agentNames.contains("SECURITY"));
    assertFalse(agentNames.contains("PROMPTS"));
  }

  @Test
  public void triageInstructionsIncludeAvailableSpecializedAgents() {
    TestableTriagePrompt prompt =
        new TestableTriagePrompt(config(), new ChangeSetData(1, -1, 1), change(false));

    String instructions = prompt.getDefaultAiAssistantInstructions();

    assertTrue(instructions.contains("# Available Specialized Agents"));
    assertTrue(instructions.contains("CORRECTNESS: Checks"));
    assertTrue(instructions.contains("SECURITY: Checks"));
    assertTrue(instructions.contains("copy only relevant complete original hunks"));
    assertTrue(instructions.contains("write each filepath once"));
    assertTrue(instructions.contains("omit `diff --git`, `index`, `---`, and `+++`"));
    assertTrue(instructions.contains("never use prose summaries"));
    assertTrue(instructions.contains("For the `COMMIT_MESSAGE` agent only"));
  }

  @Test
  public void specializedPatchsetAgentPromptReviewsPatchsetSelection() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT);
    changeSetData.setSpecializedAgentName("TESTABILITY");
    AiPromptSpecializedReviewAgent prompt =
        new AiPromptSpecializedReviewAgent(config(), changeSetData, change(false), null);

    String userMessage = prompt.getDefaultAiThreadReviewMessage("selected context");

    assertTrue(userMessage.startsWith("Review the following patchset selection:"));
    assertFalse(userMessage.startsWith("Review the following Commit Message:"));
  }

  @Test
  public void specializedPatchsetAgentFieldDefinitionsIncludeOnlySpecialistFields() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT);
    changeSetData.setSpecializedAgentName("TESTABILITY");
    changeSetData.setSpecializedAgentInstructions("Review testability only.");
    AiPromptSpecializedReviewAgent prompt =
        new TestableSpecializedPrompt(config(), changeSetData, change(false));

    String fieldDefinitions =
        extractSection(prompt.getDefaultAiAssistantInstructions(), "Field Definitions");

    assertTrue(fieldDefinitions.contains("# Field Definitions"));
    assertTrue(fieldDefinitions.contains("`reply`"));
    assertTrue(fieldDefinitions.contains("`score`"));
    assertTrue(fieldDefinitions.contains("`filename`"));
    assertTrue(fieldDefinitions.contains("`lineNumber`"));
    assertTrue(fieldDefinitions.contains("`codeSnippet`"));
    assertFalse(fieldDefinitions.contains("`changeId`"));
    assertFalse(fieldDefinitions.contains("`relevance`"));
    assertFalse(fieldDefinitions.contains("`duplicated`"));
    assertFalse(fieldDefinitions.contains("`repeated`"));
    assertFalse(fieldDefinitions.contains("`conflicting`"));
    assertFalse(fieldDefinitions.contains("`source_agent`"));
    assertFalse(fieldDefinitions.contains("`duplicated_reason`"));
    assertFalse(fieldDefinitions.contains("`repeated_reason`"));
    assertFalse(fieldDefinitions.contains("`conflicting_reason`"));
  }

  @Test
  public void commitMessageSpecialistFieldDefinitionsExcludePatchsetLocationFields() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    AiPromptSpecializedReviewAgent prompt =
        new TestableSpecializedPrompt(config(), changeSetData, change(false));

    String fieldDefinitions =
        extractSection(prompt.getDefaultAiAssistantInstructions(), "Field Definitions");

    assertTrue(fieldDefinitions.contains("# Field Definitions"));
    assertTrue(fieldDefinitions.contains("`reply`"));
    assertTrue(fieldDefinitions.contains("`score`"));
    assertFalse(fieldDefinitions.contains("`filename`"));
    assertFalse(fieldDefinitions.contains("`lineNumber`"));
    assertFalse(fieldDefinitions.contains("`codeSnippet`"));
    assertFalse(fieldDefinitions.contains("`changeId`"));
    assertFalse(fieldDefinitions.contains("`relevance`"));
  }

  @Test
  public void specializedInputIncludesTriageSelectedOriginalHunks() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    SpecializedReviewTriage.AgentPlan plan = plan("TESTABILITY", true);
    plan.setPatchsetContext(readTestResource(SELECTED_PATCHSET_CONTEXT_RESOURCE));

    String specializedInput =
        client.buildSpecializedInput(readTestResource(PATCH_SET_RESOURCE), plan);

    assertFalse(specializedInput.contains("# Triage decision"));
    assertTrue(specializedInput.contains("# Selected patchset hunks"));
    assertTrue(specializedInput.contains("a.py\n"));
    assertTrue(specializedInput.contains("@@ -1,3 +1,3 @@"));
    assertTrue(specializedInput.contains("-    return value.strip()"));
    assertTrue(specializedInput.contains("+    return value.strip().lower()"));
    assertFalse(specializedInput.contains("diff --git"));
    assertFalse(specializedInput.contains("index 1111111..2222222"));
    assertFalse(specializedInput.contains("--- a/a.py"));
    assertFalse(specializedInput.contains("+++ b/a.py"));
  }

  @Test
  public void commitMessageSpecialistReceivesTriagePatchsetSummary() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    SpecializedReviewTriage.AgentPlan plan = plan("COMMIT_MESSAGE", true);
    plan.setPatchsetContext("Changes parsing to normalize values to lowercase.");

    String specializedInput =
        client.buildSpecializedInput(readTestResource(PATCH_SET_RESOURCE), plan);

    assertTrue(specializedInput.contains("# Patchset summary"));
    assertTrue(specializedInput.contains("Changes parsing to normalize values to lowercase."));
    assertFalse(specializedInput.contains("# Selected patchset hunks"));
    assertFalse(specializedInput.contains("diff --git"));
  }

  @Test
  public void collectorInputIncludesOnlySpecializedReplyFields() {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    AiReplyItem reply =
        AiReplyItem.builder()
            .reply("Review issue")
            .score(-1.0)
            .relevance(1.0)
            .repeated(true)
            .duplicated(true)
            .conflicting(true)
            .sourceAgent("SECURITY")
            .repeatedReason("History repeat")
            .duplicatedReason("Same issue")
            .conflictingReason("Conflict")
            .filename("src/Test.java")
            .lineNumber(42)
            .codeSnippet("return value;")
            .build();

    String collectorInput =
        client.buildCollectorInput(
            List.of(SpecializedReviewAgentReplies.from("CORRECTNESS", List.of(reply))));

    assertTrue(collectorInput.contains("\"reply\":\"Review issue\""));
    assertTrue(collectorInput.contains("\"score\":-1.0"));
    assertTrue(collectorInput.contains("\"filename\":\"src/Test.java\""));
    assertTrue(collectorInput.contains("\"lineNumber\":42"));
    assertTrue(collectorInput.contains("\"codeSnippet\":\"return value;\""));
    assertFalse(collectorInput.contains("relevance"));
    assertFalse(collectorInput.contains("repeated"));
    assertFalse(collectorInput.contains("duplicated"));
    assertFalse(collectorInput.contains("conflicting"));
    assertFalse(collectorInput.contains("source_agent"));
    assertFalse(collectorInput.contains("_reason"));
  }

  private static SpecializedReviewTriage triage(SpecializedReviewTriage.AgentPlan... plans) {
    SpecializedReviewTriage triage = new SpecializedReviewTriage();
    triage.setAgents(List.of(plans));
    return triage;
  }

  private static SpecializedReviewTriage.AgentPlan plan(
      String agent, boolean enabled) {
    SpecializedReviewTriage.AgentPlan plan = new SpecializedReviewTriage.AgentPlan();
    plan.setAgent(agent);
    plan.setEnabled(enabled);
    plan.setReason(agent + " reason");
    plan.setPatchsetContext(agent + " patchset context");
    plan.setHistoryContext(agent + " history context");
    plan.setCustomInstructions(agent + " custom instructions");
    return plan;
  }

  private static GerritChange change(boolean commentEvent) {
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(commentEvent);
    when(change.getFullChangeId()).thenReturn("change~1");
    return change;
  }

  private static Configuration config() {
    Configuration config = mock(Configuration.class);
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    when(config.getAiReviewPatchSet()).thenReturn(true);
    return config;
  }

  private static String readTestResource(String resourceName) throws Exception {
    return Files.readString(TEST_RESOURCES_PATH.resolve(resourceName));
  }

  private static String extractSection(String instructions, String title) {
    String marker = "# " + title;
    int start = instructions.indexOf(marker);
    assertTrue("Expected section " + marker, start >= 0);
    int next = instructions.indexOf("\n\n# ", start + marker.length());
    return next < 0 ? instructions.substring(start) : instructions.substring(start, next);
  }

  private static class RecordingSpecializedClient extends LangChainSpecializedAgentReviewClient {
    private final List<String> recordedAgents = new ArrayList<>();
    private final List<String> collectorAgents = new ArrayList<>();
    private SpecializedReviewTriage triage = triage();
    private boolean triageCalled;
    private boolean suggestClientCalled;

    RecordingSpecializedClient(Configuration config) {
      super(config, null, null, null, Runnable::run);
    }

    @Override
    protected SpecializedReviewTriage askTriage(
        ChangeSetData changeSetData, GerritChange change, String patchSet) {
      triageCalled = true;
      return triage;
    }

    @Override
    protected AiResponseContent askSpecializedAgent(
        ChangeSetData changeSetData,
        GerritChange change,
        String patchSet,
        SpecializedReviewTriage.AgentPlan plan) {
      recordedAgents.add(plan.getAgent());
      AiResponseContent response = new AiResponseContent("");
      response.setReplies(
          List.of(AiReplyItem.builder().reply(plan.getAgent()).score(-1.0).build()));
      return response;
    }

    @Override
    protected AiResponseContent askCollector(
        ChangeSetData changeSetData,
        GerritChange change,
        List<SpecializedReviewAgentReplies> specializedReplies) {
      specializedReplies.forEach(reply -> collectorAgents.add(reply.getAgent()));
      AiResponseContent response = new AiResponseContent("");
      response.setReplies(
          List.of(
              AiReplyItem.builder()
                  .reply("Collected review")
                  .score(-1.0)
                  .relevance(1.0)
                  .build()));
      return response;
    }

    @Override
    protected LangChainSuggestClient getSuggestClient() {
      return new LangChainSuggestClient(new LangChainClient(null, null, null, null)) {
        @Override
        public AiResponseContent ask(
            ChangeSetData changeSetData, GerritChange change, String patchSet) {
          suggestClientCalled = true;
          AiResponseContent response = new AiResponseContent("");
          response.setReplies(List.of(AiReplyItem.builder().reply("suggestion").build()));
          return response;
        }
      };
    }
  }

  private static class TestableTriagePrompt extends AiPromptSpecializedReviewTriage {
    TestableTriagePrompt(
        Configuration config, ChangeSetData changeSetData, GerritChange change) {
      super(config, changeSetData, change, null);
    }

    @Override
    protected String getScopeAndReviewConstraints() {
      return "";
    }

    @Override
    protected String getAiAssistantInstructionsReview(boolean... ruleFilter) {
      return "";
    }
  }

  private static class TestableSpecializedPrompt extends AiPromptSpecializedReviewAgent {
    TestableSpecializedPrompt(
        Configuration config, ChangeSetData changeSetData, GerritChange change) {
      super(config, changeSetData, change, null);
    }

    @Override
    protected String getScopeAndReviewConstraints() {
      return "";
    }

    @Override
    protected String getAiAssistantInstructionsReviewWithoutDirectives(boolean... ruleFilter) {
      return "";
    }
  }
}
