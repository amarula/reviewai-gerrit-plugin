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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.AiRequestCancellation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.SuggestedFix;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.listener.AiReviewApplicabilityChecker;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.utils.DiffStats;
import java.util.List;
import org.junit.Test;

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
  public void closesAConcernWhoseFileLeftTheChange() {
    GerritChange change = change();
    AiResponseContent response = responseWithConcern(change, "PRESENT", "Gone.java");

    reviewerWithPatchSetFiles(List.of("src/Present.java"))
        .dismissConcernsWithoutFiles(response, change);

    ReviewConcern concern = firstConcern(response, change);
    assertEquals(ConcernStatus.DISMISSED, concern.getStatus());
    assertEquals(
        "a ledger read later must be able to tell this apart from a user's dismissal",
        Boolean.TRUE,
        concern.getAutomaticDismissal());
  }

  @Test
  public void keepsAConcernWhoseFileIsStillInTheChange() {
    GerritChange change = change();
    AiResponseContent response = responseWithConcern(change, "PRESENT", "src/Present.java");

    reviewerWithPatchSetFiles(List.of("src/Present.java"))
        .dismissConcernsWithoutFiles(response, change);

    ReviewConcern concern = firstConcern(response, change);
    assertEquals(ConcernStatus.PRESENT, concern.getStatus());
    assertNull(concern.getAutomaticDismissal());
  }

  @Test
  public void closingAConcernBecauseItsFileLeftDoesNotResetTheVote() {
    // The reported regression. Treating an automatic dismissal as an all-concerns-dismissed ledger
    // suppressed the neutral-to-positive conversion, so the next review silently replaced the vote
    // the author had earned with a neutral one. A dismissal ReviewAI made because the code is gone
    // is not a user overruling the AI, and must not read like one.
    GerritChange change = change();
    AiResponseContent response = responseWithConcern(change, "PRESENT", "Gone.java");
    PatchSetReviewer reviewer = reviewerWithPatchSetFiles(List.of("src/Present.java"));

    assertEquals(
        Integer.valueOf(1), reviewer.getReviewScore(change, responseWithoutFileDismissal(change)));

    reviewer.dismissConcernsWithoutFiles(response, change);

    assertEquals(Integer.valueOf(1), reviewer.getReviewScore(change, response));
  }

  @Test
  public void closingAConcernBecauseItsFileLeftTellsTheAuthorWhichFile() {
    // The dismissal is otherwise invisible: the concern stops being published, and its thread
    // cannot be resolved because the file is no longer there to comment on, so the review would
    // come back empty with nothing explaining why.
    GerritChange change = change();
    AiResponseContent response = responseWithConcern(change, "PRESENT", "Gone.java");
    ChangeSetData changeSetData = new ChangeSetData(1);
    PatchSetReviewer reviewer =
        reviewerWithPatchSetFiles(List.of("src/Present.java"), changeSetData);

    reviewer.dismissConcernsWithoutFiles(response, change);

    assertEquals("Closed: Gone.java", changeSetData.getReviewDismissedConcernsMessage());
  }

  @Test
  public void closesAConcernWhoseSuggestedFixWasApplied() throws Exception {
    // The reported loop: the author applied exactly what ReviewAI proposed, and the next review
    // raised
    // the same concern again. The fix is in the revision, so the concern is over.
    GerritChange change = change();
    AiResponseContent response = responseWithFix(change, ConcernStatus.PRESENT);
    ChangeSetData changeSetData = changeSetDataWithDelta(APPLIED_FIX);
    PatchSetReviewer reviewer =
        reviewerWithPatchSetFiles(List.of("A.kt"), changeSetData, APPLIED_FIX);

    reviewer.closeConcernsWithAppliedFixes(response, change);

    ReviewConcern concern = firstConcern(response, change);
    assertEquals(ConcernStatus.FIXED, concern.getStatus());
    assertEquals(
        "not DISMISSED: dismissal suppresses the positive vote, and an accepted fix is the opposite",
        Boolean.FALSE,
        Boolean.TRUE.equals(concern.getAutomaticDismissal()));
  }

  @Test
  public void overridesAReviewerThatStillCallsTheConcernPresent() throws Exception {
    // The guarantee. The reviewer held the applied fix to a stricter standard the second time
    // round,
    // and that is exactly the judgement this step must not defer to: the author did what they were
    // told.
    GerritChange change = change();
    AiResponseContent response = responseWithFix(change, ConcernStatus.PRESENT);
    PatchSetReviewer reviewer =
        reviewerWithPatchSetFiles(
            List.of("A.kt"), changeSetDataWithDelta(APPLIED_FIX), APPLIED_FIX);

    reviewer.closeConcernsWithAppliedFixes(response, change);

    assertEquals(ConcernStatus.FIXED, firstConcern(response, change).getStatus());
  }

  @Test
  public void keepsAConcernWhoseSuggestedFixWasNotApplied() throws Exception {
    GerritChange change = change();
    AiResponseContent response = responseWithFix(change, ConcernStatus.PRESENT);
    ChangeSetData changeSetData = changeSetDataWithDelta(UNRELATED_DELTA);
    PatchSetReviewer reviewer =
        reviewerWithPatchSetFiles(List.of("A.kt"), changeSetData, UNRELATED_DELTA);

    reviewer.closeConcernsWithAppliedFixes(response, change);

    assertEquals(ConcernStatus.PRESENT, firstConcern(response, change).getStatus());
    assertNull(
        "nothing was closed, so there is nothing to tell the author about",
        changeSetData.getReviewAppliedFixesMessage());
  }

  @Test
  public void tellsTheAuthorWhichConcernTheAppliedFixClosed() throws Exception {
    GerritChange change = change();
    AiResponseContent response = responseWithFix(change, ConcernStatus.PRESENT);
    ChangeSetData changeSetData = changeSetDataWithDelta(APPLIED_FIX);
    PatchSetReviewer reviewer =
        reviewerWithPatchSetFiles(List.of("A.kt"), changeSetData, APPLIED_FIX);

    reviewer.closeConcernsWithAppliedFixes(response, change);

    assertEquals("Closed: A.kt", changeSetData.getReviewAppliedFixesMessage());
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

  /** The same response with the concern still open, so the vote is taken before the dismissal. */
  private static AiResponseContent responseWithoutFileDismissal(GerritChange change) {
    return responseWithConcern(change, "PRESENT", "src/Present.java");
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
    return reviewerWithPatchSetFiles(patchSetFiles, new ChangeSetData(1));
  }

  private static PatchSetReviewer reviewerWithPatchSetFiles(
      List<String> patchSetFiles, ChangeSetData changeSetData) {
    return reviewerWithPatchSetFiles(patchSetFiles, changeSetData, null);
  }

  private static PatchSetReviewer reviewerWithPatchSetFiles(
      List<String> patchSetFiles, ChangeSetData changeSetData, String patchSetText) {
    IGerritClientPatchSet patchSet = mock(IGerritClientPatchSet.class);
    when(patchSet.getPatchSetFiles()).thenReturn(patchSetFiles);
    GerritClientData clientData = mock(GerritClientData.class);
    when(clientData.getGerritClientPatchSet()).thenReturn(patchSet);
    GerritClient gerritClient = mock(GerritClient.class);
    when(gerritClient.getClientData(any())).thenReturn(clientData);
    if (patchSetText != null) {
      try {
        when(gerritClient.getPatchSet(any(GerritChange.class))).thenReturn(patchSetText);
      } catch (Exception e) {
        throw new IllegalStateException(e);
      }
    }

    Configuration config = mock(Configuration.class);
    when(config.isVotingEnabled()).thenReturn(true);
    when(config.getConvertNeutralReviewScoreToPositive()).thenReturn(true);
    return new PatchSetReviewer(
        gerritClient,
        config,
        changeSetData,
        Providers.of(mock(GerritClientReview.class)),
        mock(IAiClient.class),
        localizer(),
        mock(PatchSetReviewConversationRecorder.class),
        mock(ReviewConcernPublisher.class),
        mock(ReviewFeedbackLifecycle.class),
        mock(AiReviewApplicabilityChecker.class),
        null);
  }

  /**
   * A localizer that behaves like the real one for formatting.
   *
   * <p>A bare mock returns null for every key, and the formatter runs the result through {@code
   * String.format}, so a null template throws rather than producing a message.
   */
  private static Localizer localizer() {
    Localizer localizer = mock(Localizer.class);
    when(localizer.getText(any()))
        .thenAnswer(
            invocation -> {
              String key = invocation.getArgument(0);
              return switch (key) {
                case "message.review.concerns.dismissed" -> "Closed: %s";
                case "message.review.concerns.fix.applied" -> "Closed: %s";
                case "message.review.comments.not.anchored" -> "Not anchored: %s";
                default -> "";
              };
            });
    return localizer;
  }

  private static ChangeSetData changeSetDataWithDelta(String delta) {
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setIncrementalPatchSet(delta);
    return changeSetData;
  }

  private static AiResponseContent responseWithFix(GerritChange change, ConcernStatus status) {
    SuggestedFix fix = new SuggestedFix();
    fix.setFilename("A.kt");
    fix.setCode("val applied = true");
    fix.setResolvedWhen("the key survives a transient failure");
    ReviewConcern concern = new ReviewConcern();
    concern.setId("c1");
    concern.setStatus(status);
    concern.setSuggestedFix(fix);
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

  private static final String APPLIED_FIX =
      """
      diff --git a/A.kt b/A.kt
      --- a/A.kt
      +++ b/A.kt
      @@ -1,1 +1,1 @@
      +    val applied = true
      """;

  private static final String UNRELATED_DELTA =
      """
      diff --git a/A.kt b/A.kt
      --- a/A.kt
      +++ b/A.kt
      @@ -1,1 +1,1 @@
      +    val somethingElse = true
      """;

  private static GerritChange change() {
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(false);
    return change;
  }
}
