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

package com.googlesource.gerrit.plugins.reviewai.review;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai.ReviewConcernReplyMapper;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.AiRequestCancellation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.listener.AiReviewApplicabilityChecker;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.utils.DiffStats;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class PatchSetReviewerTest {
  @Test
  public void absentAiResponseDoesNotProduceVote() {
    PatchSetReviewer reviewer = reviewer();

    assertNull(reviewer.getReviewScore(change(), null));
  }

  @Test
  public void emptyAiResponseRetainsPositiveNeutralVote() {
    PatchSetReviewer reviewer = reviewer();

    assertEquals(Integer.valueOf(1), reviewer.getReviewScore(change(), new AiResponseContent("")));
  }

  @Test
  public void allDismissedConcernsResetVoteToNeutral() {
    PatchSetReviewer reviewer = reviewer();
    GerritChange change = change();
    AiResponseContent response = new AiResponseContent("");
    ReviewConcern dismissedConcern = new ReviewConcern();
    dismissedConcern.setStatus(ConcernStatus.DISMISSED);
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setConcerns(List.of(dismissedConcern));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(reviewerConcerns));
    PendingReviewConcernUpdates updates = new PendingReviewConcernUpdates();
    updates.put(change.getFullChangeId(), ledger);
    response.setPendingConcernUpdates(updates);

    assertEquals(Integer.valueOf(0), reviewer.getReviewScore(change, response));
  }

  @Test
  public void oversizedPatchSetProducesWarningWithoutVote() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getMaxReviewLines()).thenReturn(1);
    ChangeSetData changeSetData = new ChangeSetData(1);
    PatchSetReviewer reviewer = reviewer(config, changeSetData);

    AiResponseContent response = reviewer.getReviewReply(change(), twoChangedLines());

    assertNull(response);
    assertEquals(
        "Too many changes. Please consider splitting into patches smaller than 1 changed lines for"
            + " review.",
        changeSetData.getReviewSystemMessage());
    assertNull(reviewer.getReviewScore(change(), response));
  }

  @Test
  public void manySmallFilesAreNotMistakenForALargeChange() throws Exception {
    // The reported bug. Each file costs its diff --git / index / --- / +++ lines, a hunk header and
    // three lines of context before a single changed line, so the patch text runs far past the
    // limit
    // while the change itself is comfortably inside it. Counting patch lines refused this; counting
    // changed lines must not.
    int limit = 1000;
    String patch = manySmallFiles(120, 3);

    assertTrue(
        "the fixture is only meaningful if the old measure would have refused it: "
            + patch.split("\n").length
            + " patch lines for 120 changed lines",
        patch.split("\n").length > limit);

    Configuration config = mock(Configuration.class);
    when(config.getMaxReviewLines()).thenReturn(limit);
    ChangeSetData changeSetData = new ChangeSetData(1);
    PatchSetReviewer reviewer = reviewer(config, changeSetData);

    assertEquals(120, DiffStats.changedLines(patch));
    reviewer.getReviewReply(change(), patch);

    assertNull(
        "a change of 120 lines must not be refused because it spans 120 files",
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void aFollowUpMeasuresTheIncrementalPatch() throws Exception {
    // On a re-review the model reasons about the delta, so the delta is what the limit should see.
    // The
    // full patch is mostly history by then, and checking it refused follow-ups whose delta was
    // tiny.
    Configuration config = mock(Configuration.class);
    when(config.getMaxReviewLines()).thenReturn(50);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setIncrementalPatchSet(twoChangedLines());
    PatchSetReviewer reviewer = reviewer(config, changeSetData);

    reviewer.getReviewReply(change(), manySmallFiles(120, 3));

    assertNull(
        "the full patch must not be measured when the review works from the delta",
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void aFollowUpStillRefusesALargeIncrementalPatch() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getMaxReviewLines()).thenReturn(50);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setIncrementalPatchSet(manySmallFiles(120, 3));
    PatchSetReviewer reviewer = reviewer(config, changeSetData);

    reviewer.getReviewReply(change(), twoChangedLines());

    assertEquals(
        "a large delta is a large change, however small the patch it sits in",
        "Too many changes. Please consider splitting into patches smaller than 50 changed lines for"
            + " review.",
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commentMessageDoesNotSkipAiReviewForEmptyPatchSet() {
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);

    assertFalse(reviewer().shouldSkipAiReviewForEmptyPatchSet(change));
  }

  @Test(expected = AiRequestSupersededException.class)
  public void discardsCompletedAiResponseWhenReviewIsSuperseded() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getMaxReviewLines()).thenReturn(10);
    ChangeSetData changeSetData = new ChangeSetData(1);
    AiRequestCancellation cancellation = new AiRequestCancellation();
    changeSetData.setAiRequestCancellation(cancellation);
    GerritChange change = change();
    IAiClient aiClient = mock(IAiClient.class);
    when(aiClient.ask(changeSetData, change, "diff"))
        .thenAnswer(
            ignored -> {
              cancellation.requestSupersession("Superseded by patch set 2");
              return new AiResponseContent("completed response");
            });
    PatchSetReviewer reviewer =
        new PatchSetReviewer(
            mock(GerritClient.class),
            config,
            changeSetData,
            Providers.of(mock(GerritClientReview.class)),
            aiClient,
            mock(Localizer.class),
            mock(PatchSetReviewConversationRecorder.class),
            mock(ReviewConcernPublisher.class),
            mock(ReviewFeedbackLifecycle.class),
            mock(AiReviewApplicabilityChecker.class),
            null);

    reviewer.getReviewReply(change, "diff");
  }

  private static String twoChangedLines() {
    return """
        diff --git a/A.java b/A.java
        --- a/A.java
        +++ b/A.java
        @@ -1 +1,3 @@
         context
        -removed
        +added one
        +added two
        """;
  }

  /**
   * A patch of {@code fileCount} files, each changing one line, with {@code context} lines around
   * it.
   */
  private static String manySmallFiles(int fileCount, int context) {
    StringBuilder patch = new StringBuilder();
    for (int i = 0; i < fileCount; i++) {
      patch
          .append("diff --git a/File")
          .append(i)
          .append(".java b/File")
          .append(i)
          .append(".java\n")
          .append("index 0000000..1111111 100644\n")
          .append("--- a/File")
          .append(i)
          .append(".java\n")
          .append("+++ b/File")
          .append(i)
          .append(".java\n")
          .append("@@ -1,")
          .append(context + 1)
          .append(" +1,")
          .append(context + 2)
          .append(" @@\n");
      for (int line = 0; line < context; line++) {
        patch.append(" context ").append(line).append('\n');
      }
      patch.append("+added in File").append(i).append('\n');
      patch.append(" context after\n");
    }
    return patch.toString();
  }

  private static PatchSetReviewer reviewer() {
    Configuration config = mock(Configuration.class);
    when(config.isVotingEnabled()).thenReturn(true);
    when(config.getConvertNeutralReviewScoreToPositive()).thenReturn(true);
    return reviewer(config, new ChangeSetData(1));
  }

  private static PatchSetReviewer reviewer(Configuration config, ChangeSetData changeSetData) {
    Localizer localizer = mock(Localizer.class);
    return new PatchSetReviewer(
        mock(GerritClient.class),
        config,
        changeSetData,
        Providers.of(mock(GerritClientReview.class)),
        mock(IAiClient.class),
        localizer,
        mock(PatchSetReviewConversationRecorder.class),
        mock(ReviewConcernPublisher.class),
        mock(ReviewFeedbackLifecycle.class),
        mock(AiReviewApplicabilityChecker.class),
        null);
  }

  @Test
  public void detachesAConcernWhoseFileLeftTheChange() {
    GerritChange change = change();
    AiResponseContent response = responseWithConcern(change, "PRESENT", "Gone.java");

    reviewerWithPreviousConcerns(List.of("src/Present.java"), response, change)
        .detachConcernsWithoutFiles(response, change);

    ReviewConcern concern = firstConcern(response, change);
    assertEquals(ConcernStatus.DETACHED, concern.getStatus());
  }

  @Test
  public void keepsAConcernWhoseFileIsStillInTheChange() {
    GerritChange change = change();
    AiResponseContent response = responseWithConcern(change, "PRESENT", "src/Present.java");

    reviewerWithPatchSetFiles(List.of("src/Present.java"))
        .detachConcernsWithoutFiles(response, change);

    ReviewConcern concern = firstConcern(response, change);
    assertEquals(ConcernStatus.PRESENT, concern.getStatus());
  }

  @Test
  public void detachingAllConcernsAllowsAPositiveVote() {
    GerritChange change = change();
    AiResponseContent response = responseWithConcern(change, "PRESENT", "Gone.java");
    response.setReplies(List.of(AiReplyItem.builder().concernId("c1").score(-1.0).build()));
    PatchSetReviewer reviewer =
        reviewerWithPreviousConcerns(List.of("src/Present.java"), response, change);

    assertEquals(List.of(-1.0), reviewer.getReviewScores(response));
    reviewer.detachConcernsWithoutFiles(response, change);

    assertEquals(ConcernStatus.DETACHED, firstConcern(response, change).getStatus());
    assertTrue(reviewer.getReviewScores(response).isEmpty());
    assertEquals(Integer.valueOf(1), reviewer.getReviewScore(change, response));
    assertEquals(
        Integer.valueOf(0),
        reviewer.getReviewScore(change, responseWithConcern(change, "DISMISSED", "Gone.java")));
  }

  @Test
  public void detachesStoredConcernWhenNoFilesRemainInThePatchSet() {
    GerritChange change = change();
    ReviewConcernLedger previousLedger =
        responseWithConcern(change, "PRESENT", "Gone.java")
            .getPendingConcernUpdates()
            .get(change.getFullChangeId())
            .orElseThrow();
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setPreviousReviewConcernLedger(previousLedger);
    ReviewConcernPublisher publisher = mock(ReviewConcernPublisher.class);
    PatchSetReviewer reviewer = reviewerWithPatchSetFiles(List.of(), changeSetData, publisher);

    reviewer.detachPreviousConcernsForEmptyPatchSet(change);

    ArgumentCaptor<AiResponseContent> responseCaptor =
        ArgumentCaptor.forClass(AiResponseContent.class);
    verify(publisher).persist(responseCaptor.capture(), eq(change));
    assertEquals(
        ConcernStatus.DETACHED, firstConcern(responseCaptor.getValue(), change).getStatus());
  }

  @Test
  public void newDeletionAndCommitMessageFindingsRemainPresentAcrossReviews() throws Exception {
    DetachmentRegression fixture = readDetachmentRegression();
    GerritChange change = change();
    ChangeSetData data = new ChangeSetData(1);
    data.setPreviousReviewConcernLedger(fixture.previousLedger);
    ReviewerConcerns previous = fixture.previousLedger.getReviewers().get(0);
    List<ReviewConcern> concerns = new ArrayList<>();
    for (ReviewConcern concern : previous.getConcerns()) {
      ReviewConcern reviewed = concern.copy();
      reviewed.setStatus(ConcernStatus.FIXED);
      concerns.add(reviewed);
    }
    List<ReviewConcern> newConcerns =
        fixture.replies.stream()
            .map(
                reply ->
                    ReviewConcernReplyMapper.fromReply(
                        reply, previous.getReviewer(), reply.getConcernId()))
            .toList();
    concerns.addAll(newConcerns);
    ReviewerConcerns merged = new ReviewerConcerns();
    merged.setReviewer(previous.getReviewer());
    merged.setConcerns(concerns);
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(merged));
    AiResponseContent response = new AiResponseContent("");
    PendingReviewConcernUpdates updates = new PendingReviewConcernUpdates();
    updates.put(change.getFullChangeId(), ledger);
    response.setPendingConcernUpdates(updates);
    response.setReplies(fixture.replies);
    PatchSetReviewer reviewer =
        reviewerWithPatchSetFiles(fixture.patchSetFiles, data, mock(ReviewConcernPublisher.class));

    reviewer.detachConcernsWithoutFiles(response, change);

    ReviewConcernLedger updated = updates.get(change.getFullChangeId()).orElseThrow();
    List<ReviewConcern> updatedConcerns = updated.getReviewers().get(0).getConcerns();
    assertEquals(
        List.of(
            ConcernStatus.DETACHED,
            ConcernStatus.DETACHED,
            ConcernStatus.PRESENT,
            ConcernStatus.PRESENT,
            ConcernStatus.PRESENT),
        updatedConcerns.stream().map(ReviewConcern::getStatus).toList());
    ReviewConcern deletionConcern = updatedConcerns.get(3);
    assertEquals("classregistry.py", deletionConcern.getLocations().get(0).getFilename());
    assertEquals("/PATCHSET_LEVEL", deletionConcern.getLocations().get(1).getFilename());
    assertEquals(List.of(-0.9, -0.85, -0.7), reviewer.getReviewScores(response));

    List<String> newFindingTexts = fixture.replies.stream().map(AiReplyItem::getReply).toList();
    assertEquals(
        newFindingTexts,
        reviewer.retrieveReviewBatches(response, change).stream()
            .map(ReviewBatch::getContent)
            .toList());
    assertEquals(fixture.detachedNotice, data.getReviewDetachedConcernsMessage());

    // Detached concerns remain unpublished even if the response includes their text.
    List<AiReplyItem> replies = new ArrayList<>(fixture.replies);
    updatedConcerns.stream()
        .filter(concern -> concern.getStatus() == ConcernStatus.DETACHED)
        .map(ReviewConcernReplyMapper::toReply)
        .forEach(replies::add);
    response.setReplies(replies);
    data.setReplyFilterEnabled(false);
    assertEquals(
        newFindingTexts,
        reviewer.retrieveReviewBatches(response, change).stream()
            .map(ReviewBatch::getContent)
            .toList());

    // Simulate loading the published ledger on the next review of the same change.
    ReviewConcernLedger stored =
        getGson().fromJson(getGson().toJson(updated), ReviewConcernLedger.class);
    data.setPreviousReviewConcernLedger(stored);
    updates.replace(change.getFullChangeId(), stored);
    reviewer.detachConcernsWithoutFiles(response, change);
    assertEquals(updated, updates.get(change.getFullChangeId()).orElseThrow());

    // New findings with unavailable anchors do not imply anything was skipped.
    ReviewerConcerns current = new ReviewerConcerns();
    current.setReviewer(previous.getReviewer());
    current.setConcerns(updatedConcerns.subList(2, 5));
    ReviewConcernLedger currentLedger = new ReviewConcernLedger();
    currentLedger.setReviewers(List.of(current));
    updates.replace(change.getFullChangeId(), currentLedger);
    response.setReplies(fixture.replies);
    assertEquals(
        newFindingTexts,
        reviewer.retrieveReviewBatches(response, change).stream()
            .map(ReviewBatch::getContent)
            .toList());
    assertNull(data.getReviewDetachedConcernsMessage());
  }

  @Test
  public void publishesAndScoresRepliesWithoutConcernIds() throws Exception {
    DetachmentRegression fixture = readDetachmentRegression();
    AiReplyItem reply = fixture.replies.get(0);
    reply.setConcernId(null);
    AiResponseContent response = new AiResponseContent("");
    response.setReplies(List.of(reply));
    ChangeSetData data = new ChangeSetData(1);
    PatchSetReviewer reviewer =
        reviewerWithPatchSetFiles(fixture.patchSetFiles, data, mock(ReviewConcernPublisher.class));

    assertEquals(List.of(reply.getScore()), reviewer.getReviewScores(response));
    assertEquals(
        List.of(reply.getReply()),
        reviewer.retrieveReviewBatches(response, change()).stream()
            .map(ReviewBatch::getContent)
            .toList());
    assertNull(data.getReviewDetachedConcernsMessage());
  }

  private static DetachmentRegression readDetachmentRegression() throws IOException {
    try (var reader =
        new InputStreamReader(
            PatchSetReviewerTest.class
                .getClassLoader()
                .getResourceAsStream("__files/review/concernDetachmentRegression.json"),
            StandardCharsets.UTF_8)) {
      return getGson().fromJson(reader, DetachmentRegression.class);
    }
  }

  private static final class DetachmentRegression {
    ReviewConcernLedger previousLedger;
    List<AiReplyItem> replies;
    List<String> patchSetFiles;
    String detachedNotice;
  }

  private static AiResponseContent responseWithConcern(
      GerritChange change, String status, String filename) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId("c1");
    concern.setStatus(ConcernStatus.valueOf(status));
    ConcernLocation location = new ConcernLocation();
    location.setFilename(filename);
    concern.setLocations(List.of(location));
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setConcerns(List.of(concern));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(reviewerConcerns));
    AiResponseContent response = new AiResponseContent("");
    PendingReviewConcernUpdates updates = new PendingReviewConcernUpdates();
    updates.put(change.getFullChangeId(), ledger);
    response.setPendingConcernUpdates(updates);
    return response;
  }

  private static ReviewConcern firstConcern(AiResponseContent response, GerritChange change) {
    return response
        .getPendingConcernUpdates()
        .get(change.getFullChangeId())
        .orElseThrow()
        .getReviewers()
        .get(0)
        .getConcerns()
        .get(0);
  }

  private static PatchSetReviewer reviewerWithPatchSetFiles(List<String> patchSetFiles) {
    return reviewerWithPatchSetFiles(
        patchSetFiles, new ChangeSetData(1), mock(ReviewConcernPublisher.class));
  }

  private static PatchSetReviewer reviewerWithPreviousConcerns(
      List<String> patchSetFiles, AiResponseContent response, GerritChange change) {
    ChangeSetData data = new ChangeSetData(1);
    data.setPreviousReviewConcernLedger(
        response.getPendingConcernUpdates().get(change.getFullChangeId()).orElseThrow());
    return reviewerWithPatchSetFiles(patchSetFiles, data, mock(ReviewConcernPublisher.class));
  }

  private static PatchSetReviewer reviewerWithPatchSetFiles(
      List<String> patchSetFiles, ChangeSetData changeSetData, ReviewConcernPublisher publisher) {
    IGerritClientPatchSet patchSet = mock(IGerritClientPatchSet.class);
    when(patchSet.getPatchSetFiles()).thenReturn(patchSetFiles);
    GerritClientData clientData = mock(GerritClientData.class);
    when(clientData.getGerritClientPatchSet()).thenReturn(patchSet);
    GerritClient gerritClient = mock(GerritClient.class);
    when(gerritClient.getClientData(any())).thenReturn(clientData);

    Configuration config = mock(Configuration.class);
    when(config.isVotingEnabled()).thenReturn(true);
    when(config.getConvertNeutralReviewScoreToPositive()).thenReturn(true);
    when(config.getLocaleDefault()).thenReturn(Locale.ROOT);
    return new PatchSetReviewer(
        gerritClient,
        config,
        changeSetData,
        Providers.of(mock(GerritClientReview.class)),
        mock(IAiClient.class),
        new Localizer(config),
        mock(PatchSetReviewConversationRecorder.class),
        publisher,
        mock(ReviewFeedbackLifecycle.class),
        mock(AiReviewApplicabilityChecker.class),
        null);
  }

  private static GerritChange change() {
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(false);
    return change;
  }
}
