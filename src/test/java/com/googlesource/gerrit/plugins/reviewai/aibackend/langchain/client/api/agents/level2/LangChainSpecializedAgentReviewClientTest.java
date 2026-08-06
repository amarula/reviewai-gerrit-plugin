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

import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewAgent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewTriage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinitions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainSuggestClient;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LangChainSpecializedAgentReviewClientTest {
  private static final Path TEST_RESOURCES_PATH = TestResourceLoader.getTestResourcePath();
  private static final String PATCH_SET_RESOURCE = "__files/langchain/suggestOriginalPatchSet.txt";
  private static final String TRIAGE_RESPONSE_RESOURCE =
      "__files/langchain/specializedTriageResponse.json";
  private static final String WRAPPED_TRIAGE_RESPONSE_RESOURCE =
      "__files/langchain/specializedTriageWrappedResponse.json";
  private static final String ROUTER_HISTORY_EXPECTED_MESSAGES_RESOURCE =
      "__files/langchain/routerAiDataPromptWithHistoryExpectedMessages.txt";
  private static final String PATCH_SET_FORGET_THREAD_RESOURCE =
      "__files/aibackend/common/client/prompt/patchSetHistoryStartsAfterLatestForgetThreadCommand.json";
  private static final String SUGGEST_PREVIOUS_REVIEW_CONTEXT_RESOURCE =
      "__files/langchain/suggestPreviousReviewContextAfterForget.json";
  private static final String MESSAGE_RESPONSE_RESOURCE =
      "__files/langchain/messageResponse.json";
  private static final String INCREMENTAL_PATCH_RESOURCE =
      "__files/langchain/newIssueIncrementalPatch.txt";
  private static final String FULL_PATCH_RESOURCE =
      "__files/langchain/newIssueFullPatch.txt";

  @Test
  public void reviewRunsEnabledSpecializedAgentsAndCollector() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    client.triage =
        triage(
            plan("CORRECTNESS", true),
            plan("SECURITY", false));
    ChangeSetData changeSetData = new ChangeSetData(1);
    GerritChange change = change(false);

    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(PATCH_SET_RESOURCE));

    assertNotNull(response.getReplies());
    assertEquals(List.of("CORRECTNESS"), client.recordedAgents);
    assertEquals(List.of("CORRECTNESS"), client.collectorAgents);
    assertEquals(List.of(true), client.historicalRepetitionSelections);
    assertEquals("Collected review", response.getReplies().getFirst().getReply());
    ReviewerConcerns stored =
        reviewer(
            pendingLedger(response),
            ConcernReviewerId.Kind.SPECIALIZED_AGENT,
            "CORRECTNESS");
    assertEquals(1, stored.getConcerns().size());
    assertEquals(
        response.getReplies().getFirst().getConcernId(),
        stored.getConcerns().getFirst().getId());
  }

  @Test
  public void firstReviewStoresOnlyConcernPublishedByCollector() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    client.triage =
        triage(
            plan("CORRECTNESS", true),
            plan("SECURITY", true));

    AiResponseContent response =
        client.ask(new ChangeSetData(1), change(false), readTestResource(PATCH_SET_RESOURCE));

    ReviewConcernLedger ledger = pendingLedger(response);
    assertEquals(1, ledger.getReviewers().size());
    reviewer(
        ledger,
        ConcernReviewerId.Kind.SPECIALIZED_AGENT,
        "CORRECTNESS");
    assertTrue(
        ledger.getReviewers().stream()
            .noneMatch(entry -> "SECURITY".equals(entry.getReviewer().getName())));
  }

  @Test
  public void followUpRunsConcernReviewBeforeNewIssueFinderForEachSpecialist()
      throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    client.triage =
        triage(
            plan("CORRECTNESS", true),
            plan("COMMIT_MESSAGE", false));
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setPreviousReviewConcernLedger(specializedLedger());
    String incrementalPatch = readTestResource(INCREMENTAL_PATCH_RESOURCE);
    String fullPatch = readTestResource(FULL_PATCH_RESOURCE);
    changeSetData.setIncrementalPatchSet(incrementalPatch);

    AiResponseContent response = client.ask(changeSetData, change(false), fullPatch);

    assertEquals(
        List.of(
            "review-CORRECTNESS",
            "find-CORRECTNESS",
            "review-COMMIT_MESSAGE",
            "find-COMMIT_MESSAGE"),
        client.concernEvents);
    assertEquals(List.of(incrementalPatch, incrementalPatch), client.incrementalPatches);
    assertEquals(List.of(fullPatch, fullPatch), client.fullPatches);
    assertEquals(
        List.of(incrementalPatch, incrementalPatch), client.concernIncrementalPatches);
    assertEquals(List.of(fullPatch, fullPatch), client.concernFullPatches);
    assertEquals(List.of("CORRECTNESS", "COMMIT_MESSAGE"), client.collectorAgents);
    assertEquals(List.of(false), client.historicalRepetitionSelections);
    assertEquals(2, response.getReplies().size());
    assertTrue(response.getReplies().getFirst().isRepeated());
    assertEquals("correctness-old", response.getReplies().getFirst().getConcernId());

    ReviewConcernLedger ledger = pendingLedger(response);
    assertEquals(3, ledger.getReviewers().size());
    ReviewerConcerns correctness =
        reviewer(
            ledger,
            ConcernReviewerId.Kind.SPECIALIZED_AGENT,
            "CORRECTNESS");
    ReviewerConcerns commitMessage =
        reviewer(
            ledger,
            ConcernReviewerId.Kind.SPECIALIZED_AGENT,
            "COMMIT_MESSAGE");
    assertEquals(2, correctness.getConcerns().size());
    assertEquals(ConcernStatus.PRESENT, correctness.getConcerns().getFirst().getStatus());
    assertEquals(1, commitMessage.getConcerns().size());
    assertEquals(ConcernStatus.FIXED, commitMessage.getConcerns().getFirst().getStatus());
    reviewer(ledger, ConcernReviewerId.Kind.SCOPED_AGENT, "PATCHSET");
  }

  @Test
  public void commitMessageScopeRunsOnlyCommitMessageSpecialist() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    client.triage =
        triage(
            plan("COMMIT_MESSAGE", true),
            plan("CORRECTNESS", true));
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setReviewScope(ReviewScope.COMMIT_MESSAGE);
    GerritChange change = change(false);

    client.ask(changeSetData, change, readTestResource(PATCH_SET_RESOURCE));

    assertEquals(List.of("COMMIT_MESSAGE"), client.recordedAgents);
  }

  @Test
  public void suggestModeUsesSuggestClientWithoutRunningSpecializedReview() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    client.triage = triage(plan("CORRECTNESS", true));
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setSuggestMode(true);
    GerritChange change = change(true);

    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(PATCH_SET_RESOURCE));

    assertTrue(client.suggestClientCalled);
    assertFalse(client.triageCalled);
    assertEquals("suggestion", response.getReplies().getFirst().getReply());
  }

  @Test
  public void commentMessageUsesDedicatedMessageRequestWithoutSpecializedRouting() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    client.triage = triage(plan("CORRECTNESS", true));
    ChangeSetData changeSetData = new ChangeSetData(1);
    GerritChange change = change(true);

    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(PATCH_SET_RESOURCE));

    assertTrue(client.messageRequestCalled);
    assertFalse(client.triageCalled);
    assertEquals(List.of(), client.recordedAgents);
    assertEquals("Message response", response.getReplies().getFirst().getReply());
    assertEquals("message request", client.getRequestBody());
  }

  @Test
  public void openAiNonZdrSuggestUsesPreviousReviewContextAfterForgetThread() throws Exception {
    Configuration config = config();
    when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
    when(config.getAiProviderZdr()).thenReturn(false);
    SpecializedSuggestReviewContext suggestContext = new SpecializedSuggestReviewContext(config);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setAiDataPrompt(readTestResource(SUGGEST_PREVIOUS_REVIEW_CONTEXT_RESOURCE));

    assertTrue(suggestContext.shouldUsePreviousReviewsAsSuggestContext(changeSetData));
    String context = suggestContext.appendPreviousReviewsContext(changeSetData, "patch");
    assertTrue(context.contains("New review reply"));
    assertFalse(context.contains("Old review reply"));
  }

  @Test
  public void openAiZdrSuggestDoesNotUsePreviousReviewContext() throws Exception {
    Configuration config = config();
    when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
    when(config.getAiProviderZdr()).thenReturn(true);
    SpecializedSuggestReviewContext suggestContext = new SpecializedSuggestReviewContext(config);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setAiDataPrompt(readTestResource(SUGGEST_PREVIOUS_REVIEW_CONTEXT_RESOURCE));

    assertFalse(suggestContext.shouldUsePreviousReviewsAsSuggestContext(changeSetData));
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
    assertEquals(
        "Consolidate with prior reviewer concern about parsing behavior.",
        triage.getConsolidationContext());
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
    assertEquals("No shared consolidation context.", triage.getConsolidationContext());
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
        new TestableTriagePrompt(config(), new ChangeSetData(1), change(false));

    String instructions = prompt.getDefaultAiAssistantInstructions();

    assertTrue(instructions.contains("# Available Specialized Agents"));
    assertTrue(instructions.contains("CORRECTNESS: Checks"));
    assertTrue(instructions.contains("SECURITY: Checks"));
    assertTrue(instructions.contains("Select which specialized review agents"));
    assertTrue(instructions.contains("Do not split, summarize, copy, rewrite"));
    assertFalse(instructions.contains("patchset_context"));
  }

  @Test
  public void specializedPatchsetAgentPromptReviewsFullPatchset() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT);
    changeSetData.setSpecializedAgentName("TESTABILITY");
    AiPromptSpecializedReviewAgent prompt =
        new AiPromptSpecializedReviewAgent(config(), changeSetData, change(false), null);

    String userMessage = prompt.getDefaultAiThreadReviewMessage("full patchset");

    assertTrue(userMessage.startsWith("Analyze the following full patchset"));
    assertFalse(userMessage.startsWith("Review the following Commit Message:"));
  }

  @Test
  public void specializedPatchsetAgentFieldDefinitionsDescribeConcernFields() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT);
    changeSetData.setSpecializedAgentName("TESTABILITY");
    changeSetData.setSpecializedAgentInstructions("Review testability only.");
    AiPromptSpecializedReviewAgent prompt =
        new TestableSpecializedPrompt(config(), changeSetData, change(false));

    String fieldDefinitions =
        extractSection(prompt.getDefaultAiAssistantInstructions(), "Field Definitions");

    assertTrue(fieldDefinitions.contains("# Field Definitions"));
    assertTrue(fieldDefinitions.contains("`concerns`"));
    assertTrue(fieldDefinitions.contains("`dismissed_concerns`"));
    assertTrue(fieldDefinitions.contains("`type`"));
    assertTrue(fieldDefinitions.contains("`description`"));
    assertTrue(fieldDefinitions.contains("`reasoning`"));
    assertTrue(fieldDefinitions.contains("`preexisting`"));
    assertTrue(fieldDefinitions.contains("`locations`"));
    assertTrue(fieldDefinitions.contains("`filename`"));
    assertTrue(fieldDefinitions.contains("`lineNumber`"));
    assertTrue(fieldDefinitions.contains("`codeSnippet`"));
    assertTrue(fieldDefinitions.contains("Every commit-message reply MUST identify"));
    assertTrue(fieldDefinitions.contains("/COMMIT_MSG"));
    assertTrue(fieldDefinitions.contains("reviewai-topic-change-1/COMMIT_MSG"));
    assertTrue(fieldDefinitions.contains("must not include `reply`, `score`"));
    assertFalse(fieldDefinitions.contains("`changeId`"));
    assertTrue(fieldDefinitions.contains("`relevance`, `duplicated`, `repeated`"));
  }

  @Test
  public void commitMessageSpecialistFieldDefinitionsRequireCommitMessageLocation() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    AiPromptSpecializedReviewAgent prompt =
        new TestableSpecializedPrompt(config(), changeSetData, change(false));

    String instructions = prompt.getDefaultAiAssistantInstructions();
    String fieldDefinitions =
        extractSection(instructions, "Field Definitions");

    assertTrue(fieldDefinitions.contains("# Field Definitions"));
    assertTrue(fieldDefinitions.contains("`concerns`"));
    assertTrue(fieldDefinitions.contains("`dismissed_concerns`"));
    assertTrue(fieldDefinitions.contains("exact commit-message filename"));
    assertTrue(fieldDefinitions.contains("Every commit-message reply MUST identify"));
    assertTrue(fieldDefinitions.contains("/COMMIT_MSG"));
    assertTrue(fieldDefinitions.contains("reviewai-topic-change-1/COMMIT_MSG"));
    assertTrue(fieldDefinitions.contains("Omit `lineNumber` and `codeSnippet`"));
    assertFalse(fieldDefinitions.contains("`changeId`"));
    assertTrue(fieldDefinitions.contains("must not include `reply`, `score`"));
    assertTrue(instructions.contains("\"filename\":\"/COMMIT_MSG\""));
    assertTrue(instructions.contains("\"filename\":\"reviewai-topic-change-1/COMMIT_MSG\""));
    assertFalse(instructions.contains("empty array for commit-message concerns"));
  }

  @Test
  public void specializedInputIncludesWholePatchset() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    SpecializedReviewTriage.AgentPlan plan = plan("TESTABILITY", true);

    String specializedInput =
        client.buildSpecializedInput(readTestResource(PATCH_SET_RESOURCE), plan);

    assertFalse(specializedInput.contains("# Triage decision"));
    assertTrue(specializedInput.contains("# Patchset"));
    assertTrue(specializedInput.contains("Subject: Fix parsing"));
    assertTrue(specializedInput.contains("a.py\n"));
    assertTrue(specializedInput.contains("@@ -1,3 +1,3 @@"));
    assertTrue(specializedInput.contains("-    return value.strip()"));
    assertTrue(specializedInput.contains("+    return value.strip().lower()"));
    assertTrue(specializedInput.contains("diff --git"));
    assertTrue(specializedInput.contains("index 1111111..2222222"));
    assertTrue(specializedInput.contains("--- a/a.py"));
    assertTrue(specializedInput.contains("+++ b/a.py"));
  }

  @Test
  public void triageInputIncludesPatchsetAndMessageThread() throws Exception {
    AiHistoryFixture fixture = readAiHistoryFixture(PATCH_SET_FORGET_THREAD_RESOURCE);
    GerritClient gerritClient = mock(GerritClient.class);
    Localizer localizer = localizer();
    RecordingSpecializedClient client =
        new RecordingSpecializedClient(config(), gerritClient, localizer);
    ChangeSetData changeSetData = new ChangeSetData(1);
    GerritChange change = change(false);
    when(gerritClient.getClientData(change))
        .thenReturn(
            new GerritClientData(
                null,
                List.of(),
                new CommentData(List.of(), new HashMap<>(), mapById(fixture.patchSetComments)),
                0));

    String triageInput =
        client.buildTriageInput(changeSetData, change, readTestResource(PATCH_SET_RESOURCE));

    assertTrue(triageInput.contains("# Patchset"));
    assertTrue(triageInput.contains("Subject: Fix parsing"));
    assertTrue(triageInput.contains("# Message thread"));
    assertFalse(triageInput.contains("first question"));
    assertFalse(triageInput.contains("first answer"));
    assertFalse(triageInput.contains("/forget_thread"));
    assertTrue(triageInput.contains("second question"));
    assertTrue(triageInput.contains("second answer"));
  }

  @Test
  public void triageInputOmitsMessageThreadWhenForgetThreadIsRequested() throws Exception {
    GerritClient gerritClient = mock(GerritClient.class);
    RecordingSpecializedClient client =
        new RecordingSpecializedClient(config(), gerritClient, localizer());
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.addParsedCommand("forget_thread", Map.of());
    GerritChange change = change(false);

    String triageInput =
        client.buildTriageInput(changeSetData, change, readTestResource(PATCH_SET_RESOURCE));

    assertTrue(triageInput.contains("# Patchset"));
    assertFalse(triageInput.contains("# Message thread"));
    verify(gerritClient, never()).getClientData(change);
  }

  @Test
  public void forgetThreadSkipsPastReviewComments() {
    GerritClient gerritClient = mock(GerritClient.class);
    RecordingSpecializedClient client =
        new RecordingSpecializedClient(config(), gerritClient, localizer());
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.addParsedCommand("forget_thread", Map.of());
    GerritChange change = change(false);

    assertTrue(client.collectPastReviewComments(changeSetData, change).isEmpty());
    verify(gerritClient, never()).getClientData(change);
  }

  @Test
  public void commitMessageSpecialistReceivesWholePatchset() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    SpecializedReviewTriage.AgentPlan plan = plan("COMMIT_MESSAGE", true);

    String specializedInput =
        client.buildSpecializedInput(readTestResource(PATCH_SET_RESOURCE), plan);

    assertTrue(specializedInput.contains("# Patchset"));
    assertTrue(specializedInput.contains("Subject: Fix parsing"));
    assertTrue(specializedInput.contains("diff --git"));
    assertFalse(specializedInput.contains("# Patchset summary"));
    assertFalse(specializedInput.contains("# Selected patchset hunks"));
  }

  @Test
  public void consolidationInputIncludesSpecializedFindings() throws Exception {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    SpecializedReviewFindings findings = finding("Correctness", "Review issue");

    String consolidationInput =
        client.buildConsolidationInput(
            List.of(SpecializedReviewFindings.AgentFindings.from("CORRECTNESS", findings)),
            String.join("\n", readTestResourceLines(ROUTER_HISTORY_EXPECTED_MESSAGES_RESOURCE)));

    assertTrue(consolidationInput.contains("\"specialized_findings\""));
    assertTrue(consolidationInput.contains("\"triage_context\""));
    assertTrue(consolidationInput.contains("Can you improve the commit title?"));
    assertTrue(consolidationInput.contains("\"agent\":\"CORRECTNESS\""));
    assertTrue(consolidationInput.contains("\"description\":\"Review issue\""));
    assertTrue(consolidationInput.contains("\"filename\":\"src/Test.java\""));
    assertTrue(consolidationInput.contains("\"lineNumber\":42"));
    assertTrue(consolidationInput.contains("\"codeSnippet\":\"return value;\""));
    assertFalse(consolidationInput.contains("new_replies"));
    assertFalse(consolidationInput.contains("past_replies"));
    assertFalse(consolidationInput.contains("\"reply\""));
    assertFalse(consolidationInput.contains("\"score\""));
  }

  @Test
  public void verificationInputIncludesPatchsetAndConflictResolvedFindings() {
    RecordingSpecializedClient client = new RecordingSpecializedClient(config());
    String verificationInput = client.buildVerificationInput("Patch body", finding("Correctness", "Issue"));

    JsonObject input = JsonParser.parseString(verificationInput).getAsJsonObject();
    assertEquals("Patch body", input.get("patchset").getAsString());
    JsonArray concerns =
        input.getAsJsonObject("conflict_resolved_findings").getAsJsonArray("concerns");
    assertEquals(1, concerns.size());
    assertEquals("Issue", concerns.get(0).getAsJsonObject().get("description").getAsString());
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
    when(config.getGerritUserName()).thenReturn("reviewai");
    when(config.getGerritUserEmail()).thenReturn("");
    when(config.getIgnoreResolvedAiComments()).thenReturn(false);
    when(config.getIgnoreOutdatedInlineComments()).thenReturn(false);
    return config;
  }

  private static String readTestResource(String resourceName) throws Exception {
    return Files.readString(TEST_RESOURCES_PATH.resolve(resourceName));
  }

  private static AiHistoryFixture readAiHistoryFixture(String resourceName) throws Exception {
    return getGson().fromJson(readTestResource(resourceName), AiHistoryFixture.class);
  }

  private static List<String> readTestResourceLines(String resourceName) throws Exception {
    return Files.readAllLines(TEST_RESOURCES_PATH.resolve(resourceName));
  }

  private static HashMap<String, GerritComment> mapById(List<GerritComment> comments) {
    HashMap<String, GerritComment> commentsById = new HashMap<>();
    for (GerritComment comment : comments) {
      commentsById.put(comment.getId(), comment);
    }
    return commentsById;
  }

  private static Localizer localizer() {
    Localizer localizer = mock(Localizer.class);
    when(localizer.getText("plugin.message.prefix")).thenReturn("ReviewAI");
    when(localizer.getText("plugin.message.label")).thenReturn("Message");
    when(localizer.getText("plugin.warning.label")).thenReturn("**WARNING**");
    when(localizer.getText("plugin.error.label")).thenReturn("**ERROR**");
    when(localizer.getText("message.empty.review")).thenReturn("");
    return localizer;
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
    private final List<String> concernEvents = new ArrayList<>();
    private final List<String> concernIncrementalPatches = new ArrayList<>();
    private final List<String> concernFullPatches = new ArrayList<>();
    private final List<String> incrementalPatches = new ArrayList<>();
    private final List<String> fullPatches = new ArrayList<>();
    private final List<Boolean> historicalRepetitionSelections = new ArrayList<>();
    private SpecializedReviewTriage triage = triage();
    private boolean triageCalled;
    private boolean suggestClientCalled;
    private boolean messageRequestCalled;

    RecordingSpecializedClient(Configuration config) {
      super(config, null, null, null, Runnable::run);
    }

    RecordingSpecializedClient(
        Configuration config, GerritClient gerritClient, Localizer localizer) {
      super(config, null, gerritClient, localizer, Runnable::run);
    }

    @Override
    protected SpecializedReviewTriage askTriage(
        ChangeSetData changeSetData, GerritChange change, String patchSet) {
      triageCalled = true;
      return triage;
    }

    @Override
    protected RawReviewRequestResult askSingleRawRequest(
        ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
      messageRequestCalled = true;
      return rawReviewRequestResult(readTestResource(MESSAGE_RESPONSE_RESOURCE), "message request");
    }

    @Override
    protected SpecializedReviewFindings askSpecializedAgent(
        ChangeSetData changeSetData,
        GerritChange change,
        String patchSet,
        SpecializedReviewTriage.AgentPlan plan) {
      recordedAgents.add(plan.getAgent());
      return finding(plan.getAgent(), plan.getAgent());
    }

    @Override
    protected ReviewerConcerns reviewConcerns(
        ChangeSetData changeSetData,
        GerritChange change,
        ReviewerConcerns existingConcerns,
        String incrementalPatchSet,
        String fullPatchSet) {
      concernEvents.add("review-" + existingConcerns.getReviewer().getName());
      concernIncrementalPatches.add(incrementalPatchSet);
      concernFullPatches.add(fullPatchSet);
      ReviewerConcerns reviewed = new ReviewerConcerns();
      reviewed.setReviewer(existingConcerns.getReviewer());
      reviewed.setConcerns(
          existingConcerns.getConcerns().stream()
              .map(
                  concern -> {
                    ReviewConcern updated = concern.copy();
                    updated.setStatus(
                        "COMMIT_MESSAGE".equals(existingConcerns.getReviewer().getName())
                            ? ConcernStatus.FIXED
                            : ConcernStatus.PRESENT);
                    updated.setStatusReason("Confirmed by concern reviewer");
                    return updated;
                  })
              .toList());
      return reviewed;
    }

    @Override
    protected RawReviewRequestResult findNewIssuesRaw(
        ChangeSetData changeSetData,
        GerritChange change,
        ReviewerConcerns reviewedConcerns,
        String incrementalPatchSet,
        String fullPatchSet) {
      String agent = reviewedConcerns.getReviewer().getName();
      concernEvents.add("find-" + agent);
      incrementalPatches.add(incrementalPatchSet);
      fullPatches.add(fullPatchSet);
      return rawReviewRequestResult(
          getGson().toJson(finding(agent, agent + " new concern")),
          "finder-" + agent);
    }

    @Override
    protected CollectorResult askCollectorResult(
        ChangeSetData changeSetData,
        GerritChange change,
        String patchSet,
        List<SpecializedReviewFindings.AgentFindings> specializedFindings,
        String triageContext,
        boolean includeHistoricalRepetition) {
      specializedFindings.forEach(finding -> collectorAgents.add(finding.getAgent()));
      historicalRepetitionSelections.add(includeHistoricalRepetition);
      SpecializedReviewFindings verifiedFindings = new SpecializedReviewFindings();
      verifiedFindings.setConcerns(
          specializedFindings.stream()
              .flatMap(findings -> findings.getConcerns().stream())
              .toList());
      verifiedFindings.setDismissedConcerns(List.of());
      ReviewConcern firstConcern = verifiedFindings.getConcerns().getFirst();
      ConcernLocation firstLocation = firstConcern.getLocations().getFirst();
      AiResponseContent response = new AiResponseContent("");
      response.setReplies(
          List.of(
              AiReplyItem.builder()
                  .reply("Collected review")
                  .score(-1.0)
                  .relevance(1.0)
                  .filename(firstLocation.getFilename())
                  .lineNumber(firstLocation.getLineNumber())
                  .codeSnippet(firstLocation.getCodeSnippet())
                  .build()));
      return new CollectorResult(response, verifiedFindings);
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

  private static ReviewConcernLedger specializedLedger() {
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(
        List.of(
            reviewerConcerns(
                ConcernReviewerId.Kind.SPECIALIZED_AGENT,
                "CORRECTNESS",
                storedConcern("correctness-old", ConcernStatus.PRESENT)),
            reviewerConcerns(
                ConcernReviewerId.Kind.SPECIALIZED_AGENT,
                "COMMIT_MESSAGE",
                storedConcern("commit-old", ConcernStatus.FIXED)),
            reviewerConcerns(
                ConcernReviewerId.Kind.SCOPED_AGENT,
                "PATCHSET",
                storedConcern("scoped-old", ConcernStatus.PRESENT))));
    return ledger;
  }

  private static ReviewerConcerns reviewerConcerns(
      ConcernReviewerId.Kind kind, String name, ReviewConcern concern) {
    ReviewerConcerns concerns = new ReviewerConcerns();
    concerns.setReviewer(new ConcernReviewerId(kind, name));
    concerns.setConcerns(List.of(concern));
    return concerns;
  }

  private static ReviewConcern storedConcern(String id, ConcernStatus status) {
    ReviewConcern concern = finding("Stored", id).getConcerns().getFirst();
    concern.setId(id);
    concern.setStatus(status);
    concern.setReply(id + " reply");
    return concern;
  }

  private static ReviewConcernLedger pendingLedger(AiResponseContent response) {
    return response.getPendingConcernUpdates().get("change~1").orElseThrow();
  }

  private static ReviewerConcerns reviewer(
      ReviewConcernLedger ledger, ConcernReviewerId.Kind kind, String name) {
    return ledger.getReviewers().stream()
        .filter(entry -> entry.getReviewer().equals(new ConcernReviewerId(kind, name)))
        .findFirst()
        .orElseThrow();
  }

  private static SpecializedReviewFindings finding(String type, String description) {
    SpecializedReviewFindings findings = new SpecializedReviewFindings();
    ReviewConcern concern = new ReviewConcern();
    concern.setType(type);
    concern.setDescription(description);
    concern.setReasoning("Reasoning");
    concern.setPreexisting(false);
    ConcernLocation location = new ConcernLocation();
    location.setFilename("src/Test.java");
    location.setLineNumber(42);
    location.setCodeSnippet("return value;");
    concern.setLocations(List.of(location));
    findings.setConcerns(List.of(concern));
    findings.setDismissedConcerns(List.of());
    return findings;
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

  private static class AiHistoryFixture {
    private List<GerritComment> patchSetComments = List.of();
  }
}
