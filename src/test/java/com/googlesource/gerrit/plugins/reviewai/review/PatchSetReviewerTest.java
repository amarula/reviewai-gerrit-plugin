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
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.listener.AiReviewApplicabilityChecker;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
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

    assertEquals(
        Integer.valueOf(1),
        reviewer.getReviewScore(change(), new AiResponseContent("")));
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
    Localizer localizer = mock(Localizer.class);
    return new PatchSetReviewer(
        mock(GerritClient.class),
        config,
        new ChangeSetData(1),
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
