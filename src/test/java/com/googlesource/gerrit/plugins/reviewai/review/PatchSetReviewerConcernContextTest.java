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

import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.util.Map;
import org.junit.Test;

public class PatchSetReviewerConcernContextTest {
  @Test
  public void forgetThreadClearsConcernsBeforeSystemOnlyResponse() throws Exception {
    GerritClient gerritClient = mock(GerritClient.class);
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.addParsedCommand("forget_thread", Map.of());
    changeSetData.setReviewSystemMessage("Conversation forgotten");
    changeSetData.setPreviousReviewConcernLedger(new ReviewConcernLedger());
    changeSetData.setIncrementalPatchSet("stale incremental patch");
    GerritClientReview clientReview = mock(GerritClientReview.class);
    IAiClient aiClient = mock(IAiClient.class);
    ReviewConcernPublisher concernPublisher = mock(ReviewConcernPublisher.class);
    GerritChange change = mock(GerritChange.class);
    PatchSetReviewer reviewer =
        new PatchSetReviewer(
            gerritClient,
            mock(Configuration.class),
            changeSetData,
            Providers.of(clientReview),
            aiClient,
            mock(Localizer.class),
            mock(PatchSetReviewConversationRecorder.class),
            concernPublisher,
            null);

    reviewer.review(change);

    verify(concernPublisher).clear(change);
    verify(concernPublisher, never()).load(change);
    verifyNoInteractions(aiClient);
    verify(clientReview).setReview(change, java.util.List.of(), changeSetData, null);
    assertNull(changeSetData.getPreviousReviewConcernLedger());
    assertNull(changeSetData.getIncrementalPatchSet());
  }
}
