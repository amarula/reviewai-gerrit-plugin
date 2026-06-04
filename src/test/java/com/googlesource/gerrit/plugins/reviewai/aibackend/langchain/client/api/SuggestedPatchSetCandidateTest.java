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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

public class SuggestedPatchSetCandidateTest {
  private static final Path TEST_RESOURCES_PATH = Paths.get("src/test/resources");
  private static final String FIXTURE_PATH = "__files/langchain/";
  private static final String EMPTY_FINAL_PATCHSET_MESSAGE_RESOURCE =
      "suggestEmptyFinalPatchSetMessage.txt";

  @Test
  public void patchsetFixIsMergedIntoOriginalPatchSet() throws IOException {
    String originalPatchSet = resource("suggestOriginalPatchSet.txt");
    AiResponseContent suggestion = suggestion(resource("suggestPatchSetFixReply.txt"));

    String candidate =
        SuggestedPatchSetCandidate.merge(
            originalPatchSet, suggestion, ReviewAssistantStage.REVIEW_CODE);

    assertTrue(candidate.contains("Subject: Fix parsing"));
    assertTrue(candidate.contains("diff --git a/a.py b/a.py"));
    assertTrue(candidate.contains("return value.strip().casefold()"));
    assertFalse(candidate.contains("+    return value.strip().lower()"));
    assertEquals(1, countOccurrences(candidate, "diff --git a/a.py b/a.py"));
    assertFalse(candidate.contains("Suggested patchset fix:"));
  }

  @Test
  public void patchsetFixThatRevertsOriginalHunkRemovesTheHunk() throws IOException {
    String originalPatchSet = resource("suggestOriginalPatchSetRevertedByFix.txt");
    AiResponseContent suggestion = suggestion(resource("suggestPatchSetFixRevertingOriginal.txt"));

    String candidate =
        SuggestedPatchSetCandidate.merge(
            originalPatchSet, suggestion, ReviewAssistantStage.REVIEW_CODE);

    assertTrue(candidate.contains("Subject: Fix BLE test setup"));
    assertFalse(candidate.contains("diff --git"));
    assertFalse(candidate.contains("SampleBleManager(bleAdapter)"));
    assertFalse(candidate.contains("registerBleConnectivityListener(this, appContext)"));
  }

  @Test
  public void patchsetFixThatRevertsPartOfOriginalHunkRecalculatesFromOriginalFile()
      throws IOException {
    String originalPatchSet = resource("suggestOriginalPatchSetRemovingImports.txt");
    AiResponseContent suggestion =
        suggestion(resource("suggestPatchSetFixReintroducingImports.txt"));

    String candidate =
        SuggestedPatchSetCandidate.merge(
            originalPatchSet, suggestion, ReviewAssistantStage.REVIEW_CODE);

    assertEquals(resource("suggestFinalPatchSetWithReintroducedImports.txt"), candidate);
  }

  @Test
  public void commitMessageSuggestionReplacesOriginalCommitMessage() throws IOException {
    String originalPatchSet = resource("suggestOriginalPatchSetWithCommitMessage.txt");
    AiResponseContent suggestion = suggestion(resource("suggestCommitMessageReply.txt"));

    String candidate =
        SuggestedPatchSetCandidate.merge(
            originalPatchSet, suggestion, ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);

    assertTrue(candidate.startsWith("Improve parser error handling"));
    assertTrue(candidate.contains("Explain null handling."));
    assertTrue(candidate.contains("---\ndiff --git a/a.py b/a.py"));
    assertFalse(candidate.contains("Subject: Minor fixes"));
    assertFalse(candidate.contains("Suggested commit message:"));
  }

  @Test
  public void finalPatchSetIsAppendedOnlyToPatchsetSuggestion() throws IOException {
    AiResponseContent patchSuggestion = suggestion(resource("suggestPatchSetFixReply.txt"));
    AiResponseContent commitMessageSuggestion = suggestion(resource("suggestCommitMessageReply.txt"));
    String finalPatchSet = resource("suggestFinalPatchSet.txt");

    SuggestedPatchSetCandidate.appendFinalPatchSet(
        patchSuggestion,
        finalPatchSet,
        ReviewAssistantStage.REVIEW_CODE,
        emptyFinalPatchSetMessage());
    SuggestedPatchSetCandidate.appendFinalPatchSet(
        commitMessageSuggestion,
        finalPatchSet,
        ReviewAssistantStage.REVIEW_COMMIT_MESSAGE,
        emptyFinalPatchSetMessage());

    assertTrue(patchSuggestion.getReplies().get(0).getReply().contains("Final patchset:"));
    assertTrue(patchSuggestion.getReplies().get(0).getReply().contains("diff --git a/a.py b/a.py"));
    String finalPatchSetBlock =
        patchSuggestion
            .getReplies()
            .get(0)
            .getReply()
            .substring(patchSuggestion.getReplies().get(0).getReply().indexOf("Final patchset:"));
    assertEquals(1, countOccurrences(finalPatchSetBlock, "diff --git a/a.py b/a.py"));
    assertFalse(finalPatchSetBlock.contains("Subject:"));
    assertFalse(finalPatchSetBlock.contains("Change-Id:"));
    assertFalse(commitMessageSuggestion.getReplies().get(0).getReply().contains("Final patchset:"));
  }

  @Test
  public void finalPatchSetAtDisplaySizeLimitIsAppended() throws IOException {
    AiResponseContent patchSuggestion = suggestion(resource("suggestPatchSetFixReply.txt"));
    String finalPatchSet =
        finalPatchSetOfSize(SuggestedPatchSetCandidate.MAX_DISPLAYED_FINAL_PATCHSET_SIZE_BYTES);

    SuggestedPatchSetCandidate.appendFinalPatchSet(
        patchSuggestion,
        finalPatchSet,
        ReviewAssistantStage.REVIEW_CODE,
        emptyFinalPatchSetMessage());

    assertTrue(patchSuggestion.getReplies().get(0).getReply().contains("Final patchset:"));
  }

  @Test
  public void finalPatchSetOverDisplaySizeLimitIsNotAppended() throws IOException {
    String originalSuggestion = resource("suggestPatchSetFixReply.txt");
    AiResponseContent patchSuggestion = suggestion(originalSuggestion);
    String finalPatchSet =
        finalPatchSetOfSize(SuggestedPatchSetCandidate.MAX_DISPLAYED_FINAL_PATCHSET_SIZE_BYTES + 1);

    SuggestedPatchSetCandidate.appendFinalPatchSet(
        patchSuggestion,
        finalPatchSet,
        ReviewAssistantStage.REVIEW_CODE,
        emptyFinalPatchSetMessage());

    assertEquals(originalSuggestion, patchSuggestion.getReplies().get(0).getReply());
    assertFalse(patchSuggestion.getReplies().get(0).getReply().contains("Final patchset:"));
  }

  @Test
  public void finalPatchSetIsAppendedWhenTotalMessageIsBelowSizeLimit() throws IOException {
    String finalPatchSet = resource("suggestFinalPatchSet.txt");
    String finalPatchSetBlock = finalPatchSetBlock(finalPatchSet);
    String suggestionText =
        "x".repeat(
            SuggestedPatchSetCandidate.MAX_TOTAL_MESSAGE_SIZE_BYTES
                - finalPatchSetBlock.length()
                - 1);
    AiResponseContent patchSuggestion = suggestion(suggestionText);

    SuggestedPatchSetCandidate.appendFinalPatchSet(
        patchSuggestion,
        finalPatchSet,
        ReviewAssistantStage.REVIEW_CODE,
        emptyFinalPatchSetMessage());

    assertTrue(patchSuggestion.getReplies().get(0).getReply().contains("Final patchset:"));
  }

  @Test
  public void finalPatchSetIsNotAppendedWhenTotalMessageReachesSizeLimit() throws IOException {
    String finalPatchSet = resource("suggestFinalPatchSet.txt");
    String finalPatchSetBlock = finalPatchSetBlock(finalPatchSet);
    String suggestionText =
        "x".repeat(
            SuggestedPatchSetCandidate.MAX_TOTAL_MESSAGE_SIZE_BYTES - finalPatchSetBlock.length());
    AiResponseContent patchSuggestion = suggestion(suggestionText);

    SuggestedPatchSetCandidate.appendFinalPatchSet(
        patchSuggestion,
        finalPatchSet,
        ReviewAssistantStage.REVIEW_CODE,
        emptyFinalPatchSetMessage());

    assertEquals(suggestionText, patchSuggestion.getReplies().get(0).getReply());
    assertFalse(patchSuggestion.getReplies().get(0).getReply().contains("Final patchset:"));
  }

  @Test
  public void emptyFinalPatchSetShowsRevertSystemMessage() throws IOException {
    AiResponseContent patchSuggestion = suggestion(resource("suggestPatchSetFixRevertingOriginal.txt"));
    String finalPatchSet =
        SuggestedPatchSetCandidate.merge(
            resource("suggestOriginalPatchSetRevertedByFix.txt"),
            patchSuggestion,
            ReviewAssistantStage.REVIEW_CODE);

    SuggestedPatchSetCandidate.appendFinalPatchSet(
        patchSuggestion,
        finalPatchSet,
        ReviewAssistantStage.REVIEW_CODE,
        emptyFinalPatchSetMessage());

    String reply = patchSuggestion.getReplies().get(0).getReply();
    assertEquals(emptyFinalPatchSetMessage(), reply);
    assertFalse(reply.contains("Final patchset:"));
    assertFalse(reply.contains("Suggested patchset fix:"));
  }

  private AiResponseContent suggestion(String reply) {
    AiResponseContent response = new AiResponseContent("");
    response.setReplies(List.of(AiReplyItem.builder().reply(reply).build()));
    return response;
  }

  private String resource(String fixtureName) throws IOException {
    return Files.readString(TEST_RESOURCES_PATH.resolve(FIXTURE_PATH + fixtureName));
  }

  private String emptyFinalPatchSetMessage() throws IOException {
    return resource(EMPTY_FINAL_PATCHSET_MESSAGE_RESOURCE).stripTrailing();
  }

  private String finalPatchSetOfSize(int size) throws IOException {
    String finalPatchSet = resource("suggestFinalPatchSet.txt").strip();
    String displayPatchSet = finalPatchSet.substring(finalPatchSet.indexOf("diff --git ")).strip();
    return finalPatchSet + "x".repeat(size - displayPatchSet.length());
  }

  private String finalPatchSetBlock(String finalPatchSet) {
    String displayPatchSet = finalPatchSet.substring(finalPatchSet.indexOf("diff --git ")).strip();
    return "\n\nFinal patchset:\n```diff\n" + displayPatchSet + "\n```";
  }

  private int countOccurrences(String text, String value) {
    int count = 0;
    int index = text.indexOf(value);
    while (index >= 0) {
      count++;
      index = text.indexOf(value, index + value.length());
    }
    return count;
  }
}
