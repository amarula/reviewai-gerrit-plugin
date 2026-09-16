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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.AiRequestCancellation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.listener.AiReviewApplicabilityChecker;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
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

    AiResponseContent response = reviewer.getReviewReply(change(), "first line\nsecond line");

    assertNull(response);
    assertEquals(
        "Too many changes. Please consider splitting into patches smaller than 1 lines for review.",
        changeSetData.getReviewSystemMessage());
    assertNull(reviewer.getReviewScore(change(), response));
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

  private static GerritChange change() {
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(false);
    return change;
  }
}
