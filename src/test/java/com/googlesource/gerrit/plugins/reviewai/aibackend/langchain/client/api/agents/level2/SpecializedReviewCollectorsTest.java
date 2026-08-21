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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiHistoryMessageFilter;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedConflictResolution;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedConsolidation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedHistoricalRepetition;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedVerification;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewCollector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class SpecializedReviewCollectorsTest {
  @Test
  public void collectorPromptsDescribeFinalReviewStages() {
    String conflict =
        collectorInstructions(ReviewAssistantStage.REVIEW_SPECIALIZED_CONFLICT_RESOLUTION);
    String consolidation =
        collectorInstructions(ReviewAssistantStage.REVIEW_SPECIALIZED_CONSOLIDATION);
    String historicalRepetition =
        collectorInstructions(ReviewAssistantStage.REVIEW_SPECIALIZED_HISTORICAL_REPETITION);
    String verification =
        collectorInstructions(ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION);

    assertTrue(consolidation.contains("Deduplication and Consolidation"));
    assertTrue(consolidation.contains("Merge overlapping concerns"));
    assertTrue(consolidation.contains("merged_concern_ids"));
    assertTrue(consolidation.contains("owner_agent"));
    assertFalse(consolidation.contains("duplicated"));
    assertTrue(historicalRepetition.contains("Historical repeated-comment check"));
    assertTrue(historicalRepetition.contains("past review comments"));
    assertFalse(historicalRepetition.contains("repetition_reply_id"));
    assertTrue(conflict.contains("Concern/dismissed-concern conflict resolution"));
    assertTrue(conflict.contains("dismissed_concern"));
    assertTrue(conflict.contains("owner_agent"));
    assertTrue(conflict.contains("Do not reinterpret historical repetition annotations"));
    assertFalse(conflict.contains("repetition_reply_id"));
    assertTrue(verification.contains("Verification and severity estimation"));
    assertTrue(verification.contains("This plugin requires final Gerrit replies"));
    assertTrue(verification.contains("including concerns with `repeated`: true"));
    assertTrue(verification.contains("repetition_reply_id"));
    assertTrue(verification.contains("Every commit-message reply MUST identify"));
    assertTrue(verification.contains("/COMMIT_MSG"));
    assertTrue(verification.contains("reviewai-topic-change-1/COMMIT_MSG"));
    assertFalse(verification.contains("For commit-message findings, omit filename"));
    assertFalse(verification.contains("Assign `relevance`"));
  }

  @Test
  public void collectorRunsConsolidationConflictResolutionAndVerification() throws Exception {
    RecordingCollectorClient client = new RecordingCollectorClient(config());

    AiResponseContent response =
        client.askCollector(new ChangeSetData(1), change(), "Patch", sourceFindings());

    assertEquals(
        List.of(
            ReviewAssistantStage.REVIEW_SPECIALIZED_CONSOLIDATION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_HISTORICAL_REPETITION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_CONFLICT_RESOLUTION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION),
        client.stages);
    assertEquals("Verified review", response.getReplies().getFirst().getReply());
    assertTrue(client.inputs.get(0).contains("specialized_findings"));
    assertTrue(firstRawConcernId(client.inputs.get(0)).startsWith("raw-"));
    assertTrue(client.inputs.get(1).contains("raw_concerns"));
    assertTrue(client.inputs.get(1).contains("past_comments"));
    assertTrue(client.inputs.get(2).contains("consolidated_findings"));
    assertTrue(client.verificationInput.contains("conflict_resolved_findings"));
    assertTrue(client.verificationInput.contains("Patch"));
  }

  @Test
  public void topicCollectorRunsOneVerificationForEachPatchSet() throws Exception {
    RecordingCollectorClient client = new RecordingCollectorClient(config());
    client.consolidationOverride =
        findings(
            List.of(
                concern(
                    "c-r1",
                    List.of("r1"),
                    "First issue",
                    "reviewai-topic-change-1/src/One.java"),
                concern(
                    "c-r2",
                    List.of("r2"),
                    "Second issue",
                    "reviewai-topic-change-2/src/Two.java")));

    AiResponseContent response =
        client.askCollector(new ChangeSetData(1), change(), topicPatchSet(), topicSourceFindings());

    assertEquals(
        List.of(
            ReviewAssistantStage.REVIEW_SPECIALIZED_CONSOLIDATION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_HISTORICAL_REPETITION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_CONFLICT_RESOLUTION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION),
        client.stages);
    assertEquals(2, client.verificationInputs.size());
    assertTrue(client.verificationInputs.get(0).contains("reviewai-topic-change-1/"));
    assertFalse(
        client.verificationInputs.get(0).contains("ReviewAI origin: reviewai-topic-change-2/"));
    assertTrue(client.verificationInputs.get(0).contains("First issue"));
    assertFalse(client.verificationInputs.get(0).contains("Second issue"));
    assertTrue(client.verificationInputs.get(1).contains("reviewai-topic-change-2/"));
    assertFalse(
        client.verificationInputs.get(1).contains("ReviewAI origin: reviewai-topic-change-1/"));
    assertTrue(client.verificationInputs.get(1).contains("Second issue"));
    assertFalse(client.verificationInputs.get(1).contains("First issue"));
    assertEquals(
        List.of("reviewai-topic-change-1", "reviewai-topic-change-2"),
        client.verificationConversationSuffixes);
    assertEquals(2, response.getReplies().size());
    assertEquals(
        "reviewai-topic-change-1/src/Test.java", response.getReplies().get(0).getFilename());
    assertEquals(
        "reviewai-topic-change-2/src/Test.java", response.getReplies().get(1).getFilename());
  }

  @Test
  public void topicCollectorRunsVerificationStagesInParallel() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      RecordingCollectorClient client = new RecordingCollectorClient(config(), executor);
      client.consolidationOverride =
          findings(
              List.of(
                  concern(
                      "c-r1",
                      List.of("r1"),
                      "First issue",
                      "reviewai-topic-change-1/src/One.java"),
                  concern(
                      "c-r2",
                      List.of("r2"),
                      "Second issue",
                      "reviewai-topic-change-2/src/Two.java")));
      client.verificationStarted = new CountDownLatch(2);

      client.askCollector(new ChangeSetData(1), change(), topicPatchSet(), topicSourceFindings());

      assertTrue(client.verificationOverlapped.get());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void failsWholeReviewWhenFinalStageFails() {
    RecordingCollectorClient client = new RecordingCollectorClient(config());
    client.failingStage = ReviewAssistantStage.REVIEW_SPECIALIZED_CONFLICT_RESOLUTION;

    assertThrows(
        IllegalStateException.class,
        () -> client.askCollector(new ChangeSetData(1), change(), "Patch", sourceFindings()));
  }

  @Test
  public void collectorMakesFinalRepliesInheritRepeatedAnnotations() throws Exception {
    RecordingCollectorClient client = new RecordingCollectorClient(config());
    client.historicalRepeated = true;

    AiResponseContent response =
        client.askCollector(new ChangeSetData(1), change(), "Patch", sourceFindings());

    assertEquals(1, response.getReplies().size());
    assertTrue(response.getReplies().getFirst().isRepeated());
    assertEquals("p10", response.getReplies().getFirst().getRepetitionReplyId());
    assertEquals("Same concern.", response.getReplies().getFirst().getRepeatedReason());
    assertEquals(
        List.of(
            ReviewAssistantStage.REVIEW_SPECIALIZED_CONSOLIDATION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_HISTORICAL_REPETITION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_CONFLICT_RESOLUTION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION),
        client.stages);
    assertTrue(client.inputs.get(2).contains("\"repeated\":true"));
    assertTrue(client.verificationInput.contains("\"repeated\":true"));
  }

  @Test
  public void followUpCollectorSkipsHistoricalRepetition() throws Exception {
    RecordingCollectorClient client = new RecordingCollectorClient(config());
    client.historicalRepeated = true;
    client.verificationRepeated = true;

    AiResponseContent response =
        client
            .askCollectorResult(
                new ChangeSetData(1),
                change(),
                "Patch",
                sourceFindings(),
                null,
                false)
            .response();

    assertEquals(
        List.of(
            ReviewAssistantStage.REVIEW_SPECIALIZED_CONSOLIDATION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_CONFLICT_RESOLUTION,
            ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION),
        client.stages);
    assertFalse(client.verificationInput.contains("\"repeated\":true"));
    assertFalse(response.getReplies().getFirst().isRepeated());
    assertEquals("", response.getReplies().getFirst().getRepetitionReplyId());
    assertEquals("", response.getReplies().getFirst().getRepeatedReason());
  }

  @Test
  public void rawConcernIdsAreUniqueAcrossCollectorRuns() throws Exception {
    RecordingCollectorClient client = new RecordingCollectorClient(config());

    client.askCollector(new ChangeSetData(1), change(), "Patch", sourceFindings());
    String firstRunConcernId = firstRawConcernId(client.inputs.get(0));
    client.stages.clear();
    client.inputs.clear();

    client.askCollector(new ChangeSetData(1), change(), "Patch", sourceFindings());
    String secondRunConcernId = firstRawConcernId(client.inputs.get(0));

    assertTrue(firstRunConcernId.startsWith("raw-"));
    assertTrue(secondRunConcernId.startsWith("raw-"));
    assertNotEquals(firstRunConcernId, secondRunConcernId);
    assertFalse(firstRunConcernId.matches("r\\d+"));
    assertFalse(secondRunConcernId.matches("r\\d+"));
  }

  @Test
  public void historicalRepetitionPastCommentFilterDropsHistoryNoise() {
    AiHistoryMessageFilter filter = new AiHistoryMessageFilter();

    assertFalse(filter.shouldIncludeReviewComment(historyEntry("p1", "")));
    assertFalse(
        filter.shouldIncludeReviewComment(
            historyEntry("p2", "DYNAMIC CONFIGURATION SETTINGS\n\nmultiAgentMode: false")));
    assertFalse(
        filter.shouldIncludeReviewComment(
            historyEntry("p3", "```\nDYNAMIC CONFIGURATION SETTINGS\nfoo: bar\n```")));
    assertFalse(
        filter.shouldIncludeReviewComment(
            historyEntry("p4", "ReviewAI Message: Dynamic configuration modified")));
    assertFalse(
        filter.shouldIncludeReviewComment(
            historyEntry(
                "p5",
                "Uploaded patch set 2.\n\nOutdated Votes:\n* Code-Review-1")));
    assertTrue(
        filter.shouldIncludeReviewComment(
            historyEntry(
                "p6",
                "The new parser should reject null user input before dereferencing it.")));
  }

  @Test
  public void deterministicMergeMarksConsolidatedConcernRepeatedWhenAllRawConcernsRepeat() {
    RecordingCollectorClient client = new RecordingCollectorClient(config());

    SpecializedReviewFindings result =
        client.applyHistoricalRepetition(
            consolidatedConcern("c1", List.of("r1", "r2")),
            historicalRepetitionResult(
                annotation("r1", true, "p10", "Same concern."),
                annotation("r2", true, "p10", "Same concern.")));

    ReviewConcern concern = result.getConcerns().getFirst();
    assertEquals("c-r1", concern.getId());
    assertTrue(concern.getRepeated());
    assertEquals("p10", concern.getPreviousCommentId());
    assertEquals("Same concern.", concern.getRepeatedReason());
  }

  @Test
  public void deterministicMergeKeepsConsolidatedConcernActiveWhenAnyRawConcernIsNew() {
    RecordingCollectorClient client = new RecordingCollectorClient(config());

    SpecializedReviewFindings result =
        client.applyHistoricalRepetition(
            consolidatedConcern("c1", List.of("r1", "r2")),
            historicalRepetitionResult(
                annotation("r1", true, "p10", "Same concern."),
                annotation("r2", false, "", "")));

    ReviewConcern concern = result.getConcerns().getFirst();
    assertEquals("c-r1", concern.getId());
    assertFalse(concern.getRepeated());
    assertEquals("", concern.getPreviousCommentId());
    assertEquals("", concern.getRepeatedReason());
  }

  @Test
  public void staleConflictResolutionIdsFallBackToAnnotatedFindings() {
    RecordingCollectorClient client = new RecordingCollectorClient(config());
    SpecializedReviewFindings annotatedFindings =
        consolidatedConcern("c-raw-current-r1", List.of("raw-current-r1"));
    annotatedFindings.getConcerns().getFirst().setRepeated(true);
    annotatedFindings.getConcerns().getFirst().setPreviousCommentId("p10");
    annotatedFindings.getConcerns().getFirst().setRepeatedReason("Same concern.");
    SpecializedReviewFindings staleFindings =
        consolidatedConcern("c-raw-old-r1", List.of("raw-old-r1"));

    SpecializedReviewFindings result =
        client.currentRunConflictResolutionOrFallback(staleFindings, annotatedFindings);
    client.copyRepeatedAnnotations(result, annotatedFindings);

    assertEquals(List.of("raw-current-r1"), result.getConcerns().getFirst().getMergedConcernIds());
    assertTrue(result.getConcerns().getFirst().getRepeated());
    assertEquals("p10", result.getConcerns().getFirst().getPreviousCommentId());
  }

  @Test
  public void conflictResolutionCannotChangeOwnerAgent() {
    SpecializedReviewFindings annotatedFindings =
        consolidatedConcern("c-raw-current-r1", List.of("raw-current-r1"));
    SpecializedReviewFindings conflictResolvedFindings =
        consolidatedConcern("c-raw-current-r1", List.of("raw-current-r1"));
    conflictResolvedFindings.getConcerns().getFirst().setOwnerAgent("SECURITY");

    SpecializedReviewConcernOwnership.preserveOwners(
        conflictResolvedFindings, annotatedFindings);

    assertEquals(
        "CORRECTNESS",
        conflictResolvedFindings.getConcerns().getFirst().getOwnerAgent());
  }

  @Test
  public void specializedAgentFindingsDiscardItemsOutsideAgentScope() {
    ReviewConcern correctness =
        concern("raw-correctness", List.of(), "Correctness issue", "src/Test.java");
    ReviewConcern codeQuality =
        concern("raw-code-quality", List.of(), "Code quality issue", "src/Test.java");
    codeQuality.setType("Code Quality");
    ReviewConcern dismissedCorrectness =
        concern("dismissed-correctness", List.of(), "Dismissed issue", "src/Test.java");
    SpecializedReviewFindings findings = findings(List.of(correctness, codeQuality));
    findings.setDismissedConcerns(List.of(dismissedCorrectness));

    SpecializedReviewFindings.AgentFindings scoped =
        SpecializedReviewFindings.AgentFindings.from("CODE_QUALITY", findings);

    assertEquals(List.of(codeQuality), scoped.getConcerns());
    assertTrue(scoped.getDismissedConcerns().isEmpty());
  }

  @Test
  public void consolidationCannotAssignOwnerWithoutMatchingSourceAgent() {
    ReviewConcern rawCodeQuality =
        concern("raw-code-quality", List.of(), "Code quality issue", "src/Test.java");
    rawCodeQuality.setType("Code Quality");
    List<SpecializedReviewFindings.AgentFindings> rawFindings =
        List.of(
            SpecializedReviewFindings.AgentFindings.from(
                "CODE_QUALITY", findings(List.of(rawCodeQuality))));
    SpecializedReviewFindings consolidated =
        consolidatedConcern("c-raw-code-quality", List.of("raw-code-quality"));

    SpecializedReviewConcernOwnership.retainSupportedOwners(
        consolidated, rawFindings, Set.of());

    assertTrue(consolidated.getConcerns().isEmpty());
  }

  @Test
  public void consolidationCannotRestoreDisabledOwner() {
    ReviewConcern rawCorrectness =
        concern("raw-correctness", List.of(), "Correctness issue", "src/Test.java");
    List<SpecializedReviewFindings.AgentFindings> rawFindings =
        List.of(
            SpecializedReviewFindings.AgentFindings.from(
                "CORRECTNESS", findings(List.of(rawCorrectness))));
    SpecializedReviewFindings consolidated =
        consolidatedConcern("c-raw-correctness", List.of("raw-correctness"));

    SpecializedReviewConcernOwnership.retainSupportedOwners(
        consolidated, rawFindings, Set.of("CORRECTNESS"));

    assertTrue(consolidated.getConcerns().isEmpty());
  }

  @Test
  public void staleConsolidationIdsFallBackToCurrentRawConcerns() {
    RecordingCollectorClient client = new RecordingCollectorClient(config());
    String currentRawId = "raw-current-r1";
    List<SpecializedReviewFindings.AgentFindings> sourceFindings =
        List.of(
            SpecializedReviewFindings.AgentFindings.from(
                "CORRECTNESS", findings(currentRawId, List.of(), "Current issue")));

    SpecializedReviewFindings fallback =
        client.currentRunConsolidationOrFallback(
            consolidatedConcern("c-raw-old-r1", List.of("raw-old-r1")),
            sourceFindings,
            Set.of(currentRawId));
    SpecializedReviewFindings annotated =
        client.applyHistoricalRepetition(
            fallback, historicalRepetitionResult(annotation(currentRawId, true, "p10", "Same.")));

    assertTrue(annotated.getConcerns().getFirst().getRepeated());
    assertEquals("c-" + currentRawId, fallback.getConcerns().getFirst().getId());
    assertEquals(List.of(currentRawId), fallback.getConcerns().getFirst().getMergedConcernIds());
  }

  @Test
  public void staleHistoricalRepetitionIdsFallBackToCurrentRawConcerns() {
    SpecializedReviewFindings.HistoricalRepetitionResult result =
        SpecializedReviewPayloads.currentRunHistoricalRepetitionOrFallback(
            historicalRepetitionResult(annotation("raw-old-r1", true, "p10", "Same.")),
            Set.of("raw-current-r1"));

    SpecializedReviewFindings.HistoricalRepetitionAnnotation annotation =
        result.getAnnotations().getFirst();
    assertEquals(
        List.of("raw-current-r1"),
        result.getAnnotations().stream()
            .map(SpecializedReviewFindings.HistoricalRepetitionAnnotation::getConcernId)
            .toList());
    assertFalse(annotation.isRepeated());
    assertEquals("", annotation.getPastCommentId());
    assertEquals("", annotation.getReason());
  }

  @Test
  public void staleHistoricalRepetitionIdsFailStrictValidation() {
    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () ->
                SpecializedReviewPayloads.validateHistoricalRepetitionResult(
                    historicalRepetitionResult(annotation("raw-old-r1", true, "p10", "Same.")),
                    Set.of("raw-current-r1")));

    assertEquals("Invalid historical repetition concern ID", thrown.getMessage());
  }

  private static String collectorInstructions(ReviewAssistantStage stage) {
    ChangeSetData data = new ChangeSetData(1);
    data.setReviewAssistantStage(stage);
    AiPromptSpecializedReviewCollector prompt =
        switch (stage) {
          case REVIEW_SPECIALIZED_CONSOLIDATION ->
              new AiPromptSpecializedConsolidation(config(), data, change(), null);
          case REVIEW_SPECIALIZED_HISTORICAL_REPETITION ->
              new AiPromptSpecializedHistoricalRepetition(config(), data, change(), null);
          case REVIEW_SPECIALIZED_CONFLICT_RESOLUTION ->
              new AiPromptSpecializedConflictResolution(config(), data, change(), null);
          case REVIEW_SPECIALIZED_VERIFICATION ->
              new AiPromptSpecializedVerification(config(), data, change(), null);
          default -> throw new IllegalArgumentException("Not a collector stage: " + stage);
        };
    return prompt.getDefaultAiAssistantInstructions();
  }

  private static List<SpecializedReviewFindings.AgentFindings> sourceFindings() {
    return List.of(SpecializedReviewFindings.AgentFindings.from("CORRECTNESS", findings("Issue")));
  }

  private static List<SpecializedReviewFindings.AgentFindings> topicSourceFindings() {
    return List.of(
        SpecializedReviewFindings.AgentFindings.from(
            "CORRECTNESS",
            findings(
                List.of(
                    concern(
                        "r1", List.of(), "First issue", "reviewai-topic-change-1/src/One.java"),
                    concern(
                        "r2",
                        List.of(),
                        "Second issue",
                        "reviewai-topic-change-2/src/Two.java")))));
  }

  private static String topicPatchSet() {
    return String.join(
        "\n\n",
        "Review these Gerrit patch sets as one topic push. Each diff filename is prefixed.",
        String.join(
            "\n",
            "ReviewAI origin: reviewai-topic-change-1/",
            "Gerrit change: change~1",
            "Patch set: 1",
            "diff --git a/reviewai-topic-change-1/src/One.java "
                + "b/reviewai-topic-change-1/src/One.java"),
        String.join(
            "\n",
            "ReviewAI origin: reviewai-topic-change-2/",
            "Gerrit change: change~2",
            "Patch set: 1",
            "diff --git a/reviewai-topic-change-2/src/Two.java "
                + "b/reviewai-topic-change-2/src/Two.java"));
  }

  private static String firstRawConcernId(String consolidationInput) {
    return JsonParser.parseString(consolidationInput)
        .getAsJsonObject()
        .getAsJsonArray("specialized_findings")
        .get(0)
        .getAsJsonObject()
        .getAsJsonArray("concerns")
        .get(0)
        .getAsJsonObject()
        .get("id")
        .getAsString();
  }

  private static AiReviewHistoryInfo.Entry historyEntry(String id, String message) {
    return new AiReviewHistoryInfo.Entry(
        id,
        null,
        Settings.OPENAI_ROLE_ASSISTANT,
        false,
        "ReviewAI",
        "2026-06-23 10:00:00.000000000",
        1,
        "src/Test.java",
        42,
        null,
        message);
  }

  private static SpecializedReviewFindings findings(String description) {
    return findings(null, List.of(), description);
  }

  private static SpecializedReviewFindings consolidatedConcern(
      String id, List<String> mergedConcernIds) {
    SpecializedReviewFindings findings = findings(id, mergedConcernIds, "Issue");
    findings.getConcerns().getFirst().setOwnerAgent("CORRECTNESS");
    return findings;
  }

  private static SpecializedReviewFindings findings(
      String id, List<String> mergedConcernIds, String description) {
    return findings(List.of(concern(id, mergedConcernIds, description, "src/Test.java")));
  }

  private static SpecializedReviewFindings findings(
      List<ReviewConcern> concerns) {
    SpecializedReviewFindings findings = new SpecializedReviewFindings();
    findings.setConcerns(concerns);
    findings.setDismissedConcerns(List.of());
    return findings;
  }

  private static ReviewConcern concern(
      String id, List<String> mergedConcernIds, String description, String filename) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId(id);
    concern.setMergedConcernIds(mergedConcernIds);
    concern.setType("Correctness");
    concern.setDescription(description);
    concern.setReasoning("Reasoning");
    concern.setPreexisting(false);
    concern.setOwnerAgent("CORRECTNESS");
    ConcernLocation location = new ConcernLocation();
    location.setFilename(filename);
    location.setLineNumber(42);
    location.setCodeSnippet("return value;");
    concern.setLocations(List.of(location));
    return concern;
  }

  private static SpecializedReviewFindings.HistoricalRepetitionResult historicalRepetitionResult(
      SpecializedReviewFindings.HistoricalRepetitionAnnotation... annotations) {
    SpecializedReviewFindings.HistoricalRepetitionResult result =
        new SpecializedReviewFindings.HistoricalRepetitionResult();
    result.setAnnotations(List.of(annotations));
    return result;
  }

  private static SpecializedReviewFindings.HistoricalRepetitionAnnotation annotation(
      String concernId, boolean repeated, String pastCommentId, String reason) {
    SpecializedReviewFindings.HistoricalRepetitionAnnotation annotation =
        new SpecializedReviewFindings.HistoricalRepetitionAnnotation();
    annotation.setConcernId(concernId);
    annotation.setRepeated(repeated);
    annotation.setPastCommentId(pastCommentId);
    annotation.setReason(reason);
    return annotation;
  }

  private static Configuration config() {
    Configuration config = mock(Configuration.class);
    when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
    return config;
  }

  private static GerritChange change() {
    GerritChange change = mock(GerritChange.class);
    when(change.getFullChangeId()).thenReturn("change~1");
    return change;
  }

  private static class RecordingCollectorClient extends LangChainSpecializedAgentReviewClient {
    private final List<ReviewAssistantStage> stages = new ArrayList<>();
    private final List<String> inputs = new ArrayList<>();
    private final List<String> verificationInputs = new ArrayList<>();
    private final List<String> verificationConversationSuffixes = new ArrayList<>();
    private String verificationInput;
    private ReviewAssistantStage failingStage;
    private boolean historicalRepeated;
    private boolean verificationRepeated;
    private SpecializedReviewFindings consolidationOverride;
    private CountDownLatch verificationStarted;
    private final AtomicBoolean verificationOverlapped = new AtomicBoolean(true);

    RecordingCollectorClient(Configuration config) {
      super(config, null, null, null, Runnable::run);
    }

    RecordingCollectorClient(Configuration config, Executor executor) {
      super(config, null, null, null, executor);
    }

    @Override
    protected SpecializedReviewFindings askFindingsStage(
        ChangeSetData changeSetData,
        GerritChange change,
        String input,
        ReviewAssistantStage stage) {
      stages.add(stage);
      inputs.add(input);
      if (stage == failingStage) {
        throw new IllegalStateException("collector failed");
      }
      if (stage == ReviewAssistantStage.REVIEW_SPECIALIZED_CONSOLIDATION) {
        if (consolidationOverride != null) {
          return consolidationOverride;
        }
        return consolidatedConcern("c1", List.of(firstRawConcernId(input)));
      }
      if (stage == ReviewAssistantStage.REVIEW_SPECIALIZED_CONFLICT_RESOLUTION) {
        return com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson()
            .fromJson(
                JsonParser.parseString(input)
                    .getAsJsonObject()
                    .getAsJsonObject("consolidated_findings"),
                SpecializedReviewFindings.class);
      }
      return findings(stage.name());
    }

    @Override
    protected SpecializedReviewFindings.HistoricalRepetitionResult askHistoricalRepetitionStage(
        ChangeSetData changeSetData,
        GerritChange change,
        String input,
        java.util.Set<String> expectedConcernIds) {
      stages.add(ReviewAssistantStage.REVIEW_SPECIALIZED_HISTORICAL_REPETITION);
      inputs.add(input);
      if (ReviewAssistantStage.REVIEW_SPECIALIZED_HISTORICAL_REPETITION == failingStage) {
        throw new IllegalStateException("collector failed");
      }
      SpecializedReviewFindings.HistoricalRepetitionResult result =
          new SpecializedReviewFindings.HistoricalRepetitionResult();
      result.setAnnotations(
          expectedConcernIds.stream()
              .map(id -> annotation(id, historicalRepeated, "p10", "Same concern."))
              .toList());
      return result;
    }

    @Override
    protected AiResponseContent askVerificationStage(
        ChangeSetData changeSetData, GerritChange change, String input) {
      stages.add(ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION);
      verificationInput = input;
      verificationInputs.add(input);
      verificationConversationSuffixes.add(changeSetData.getReviewAssistantStageConversationSuffix());
      if (verificationStarted != null) {
        verificationStarted.countDown();
        try {
          if (!verificationStarted.await(2, TimeUnit.SECONDS)) {
            verificationOverlapped.set(false);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("Interrupted waiting for parallel verification", e);
        }
      }
      if (ReviewAssistantStage.REVIEW_SPECIALIZED_VERIFICATION == failingStage) {
        throw new IllegalStateException("verification failed");
      }
      AiResponseContent response = new AiResponseContent("");
      response.setReplies(
          List.of(
              AiReplyItem.builder()
                  .reply("Verified review")
                  .filename(replyFilename(input))
                  .lineNumber(42)
                  .score(-1.0)
                  .repeated(verificationRepeated)
                  .repetitionReplyId(verificationRepeated ? "p10" : "")
                  .repeatedReason(verificationRepeated ? "Same concern." : "")
                  .build()));
      return response;
    }

    private String replyFilename(String input) {
      if (input.contains("reviewai-topic-change-1/")) {
        return "reviewai-topic-change-1/src/Test.java";
      }
      if (input.contains("reviewai-topic-change-2/")) {
        return "reviewai-topic-change-2/src/Test.java";
      }
      return "src/Test.java";
    }
  }
}
