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

package com.googlesource.gerrit.plugins.reviewai.listener;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType.PreprocessResult;
import java.util.Optional;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class EventHandlerTypePatchSetReviewTest {
  @Parameterized.Parameters(name = "{0}")
  public static Object[] reviewableCommitMessageChangeKinds() {
    return new Object[] {ChangeKind.NO_CODE_CHANGE, ChangeKind.TRIVIAL_REBASE_WITH_MESSAGE_UPDATE};
  }

  @Parameterized.Parameter public ChangeKind changeKind;

  @Test
  public void skipsReviewWhenApplicabilityExpressionDoesNotMatch() {
    Configuration config = mock(Configuration.class);
    ChangeSetData changeSetData = mock(ChangeSetData.class);
    GerritChange change = mock(GerritChange.class);
    PatchSetReviewer reviewer = mock(PatchSetReviewer.class);
    GerritClient gerritClient = mock(GerritClient.class);
    TopicPatchSetReviewCoordinator coordinator = mock(TopicPatchSetReviewCoordinator.class);
    AiReviewApplicabilityChecker applicabilityChecker =
        mock(AiReviewApplicabilityChecker.class);
    PatchSetAttribute patchSet = new PatchSetAttribute();
    patchSet.kind = changeKind;

    when(config.getAiReviewPatchSet()).thenReturn(true);
    when(config.getAiReviewApplicableIf()).thenReturn("label:Verified>=1");
    when(change.getPatchSetAttribute()).thenReturn(Optional.of(patchSet));

    EventHandlerTypePatchSetReview handler =
        new EventHandlerTypePatchSetReview(
            config,
            changeSetData,
            change,
            reviewer,
            gerritClient,
            coordinator,
            applicabilityChecker,
            true);

    assertEquals(PreprocessResult.EXIT, handler.preprocessEvent());
    verify(applicabilityChecker).isApplicable(change, "label:Verified>=1");
    verify(gerritClient, never()).retrievePatchSetInfo(change);
  }

  @Test
  public void forcedReviewBypassesApplicabilityExpression() {
    Configuration config = mock(Configuration.class);
    ChangeSetData changeSetData = mock(ChangeSetData.class);
    GerritChange change = mock(GerritChange.class);
    PatchSetReviewer reviewer = mock(PatchSetReviewer.class);
    GerritClient gerritClient = mock(GerritClient.class);
    TopicPatchSetReviewCoordinator coordinator = mock(TopicPatchSetReviewCoordinator.class);
    AiReviewApplicabilityChecker applicabilityChecker =
        mock(AiReviewApplicabilityChecker.class);
    PatchSetAttribute patchSet = new PatchSetAttribute();
    patchSet.kind = changeKind;

    when(config.getAiReviewPatchSet()).thenReturn(true);
    when(config.getAiReviewApplicableIf()).thenReturn("label:Verified>=1");
    when(changeSetData.getForcedReview()).thenReturn(true);
    when(change.getPatchSetAttribute()).thenReturn(Optional.of(patchSet));

    EventHandlerTypePatchSetReview handler =
        new EventHandlerTypePatchSetReview(
            config,
            changeSetData,
            change,
            reviewer,
            gerritClient,
            coordinator,
            applicabilityChecker,
            true);

    assertEquals(PreprocessResult.OK, handler.preprocessEvent());
    verify(applicabilityChecker, never()).isApplicable(change, "label:Verified>=1");
    verify(gerritClient).retrievePatchSetInfo(change);
  }

  @Test
  public void reviewsCommitMessageChange() throws Exception {
    Configuration config = mock(Configuration.class);
    ChangeSetData changeSetData = mock(ChangeSetData.class);
    GerritChange change = mock(GerritChange.class);
    PatchSetReviewer reviewer = mock(PatchSetReviewer.class);
    GerritClient gerritClient = mock(GerritClient.class);
    TopicPatchSetReviewCoordinator coordinator = mock(TopicPatchSetReviewCoordinator.class);
    AiReviewApplicabilityChecker applicabilityChecker =
        mock(AiReviewApplicabilityChecker.class);
    PatchSetAttribute patchSet = new PatchSetAttribute();
    patchSet.kind = changeKind;
    patchSet.author = new AccountAttribute();
    patchSet.author.username = "author";

    when(config.getAiReviewPatchSet()).thenReturn(true);
    when(config.getAiReviewApplicableIf()).thenReturn("");
    when(config.getTopicPatchSetWaitMs()).thenReturn(0);
    when(applicabilityChecker.isApplicable(change, "")).thenReturn(true);
    when(change.getPatchSetAttribute()).thenReturn(Optional.of(patchSet));
    when(coordinator.awaitBatch(change, 0)).thenReturn(Optional.empty());

    EventHandlerTypePatchSetReview handler =
        new EventHandlerTypePatchSetReview(
            config,
            changeSetData,
            change,
            reviewer,
            gerritClient,
            coordinator,
            applicabilityChecker,
            true);

    assertEquals(PreprocessResult.OK, handler.preprocessEvent());
    handler.processEvent();

    verify(gerritClient).retrievePatchSetInfo(change);
    verify(reviewer).review(change, true);
  }

  @Test
  public void reviewsTopicBatchAsMergedReview() throws Exception {
    Configuration config = mock(Configuration.class);
    ChangeSetData changeSetData = mock(ChangeSetData.class);
    GerritChange change = mock(GerritChange.class);
    GerritChange relatedChange = mock(GerritChange.class);
    PatchSetReviewer reviewer = mock(PatchSetReviewer.class);
    GerritClient gerritClient = mock(GerritClient.class);
    TopicPatchSetReviewCoordinator coordinator = mock(TopicPatchSetReviewCoordinator.class);
    AiReviewApplicabilityChecker applicabilityChecker =
        mock(AiReviewApplicabilityChecker.class);
    PatchSetAttribute patchSet = new PatchSetAttribute();
    patchSet.kind = ChangeKind.REWORK;
    patchSet.author = new AccountAttribute();
    patchSet.author.username = "author";
    List<GerritChange> topicChanges = List.of(change, relatedChange);

    when(config.getAiReviewPatchSet()).thenReturn(true);
    when(config.getAiReviewApplicableIf()).thenReturn("");
    when(config.getTopicPatchSetWaitMs()).thenReturn(0);
    when(applicabilityChecker.isApplicable(change, "")).thenReturn(true);
    when(applicabilityChecker.isApplicable(relatedChange, "")).thenReturn(true);
    when(change.getPatchSetAttribute()).thenReturn(Optional.of(patchSet));
    when(relatedChange.getPatchSetAttribute()).thenReturn(Optional.of(patchSet));
    when(coordinator.awaitBatch(change, 0)).thenReturn(Optional.of(topicChanges));

    EventHandlerTypePatchSetReview handler =
        new EventHandlerTypePatchSetReview(
            config,
            changeSetData,
            change,
            reviewer,
            gerritClient,
            coordinator,
            applicabilityChecker,
            true);

    assertEquals(PreprocessResult.OK, handler.preprocessEvent());
    handler.processEvent();

    verify(reviewer).reviewTopic(topicChanges, true);
  }

  @Test
  public void forcedTopicReviewQueriesTopicChanges() throws Exception {
    Configuration config = mock(Configuration.class);
    ChangeSetData changeSetData = mock(ChangeSetData.class);
    GerritChange change = mock(GerritChange.class);
    GerritChange relatedChange = mock(GerritChange.class);
    PatchSetReviewer reviewer = mock(PatchSetReviewer.class);
    GerritClient gerritClient = mock(GerritClient.class);
    TopicPatchSetReviewCoordinator coordinator = mock(TopicPatchSetReviewCoordinator.class);
    AiReviewApplicabilityChecker applicabilityChecker =
        mock(AiReviewApplicabilityChecker.class);
    PatchSetAttribute patchSet = new PatchSetAttribute();
    patchSet.kind = ChangeKind.REWORK;
    patchSet.author = new AccountAttribute();
    patchSet.author.username = "author";
    List<GerritChange> topicChanges = List.of(change, relatedChange);

    when(config.getAiReviewPatchSet()).thenReturn(true);
    when(config.getAiReviewApplicableIf()).thenReturn("");
    when(changeSetData.getForcedTopicReview()).thenReturn(true);
    when(applicabilityChecker.isApplicable(change, "")).thenReturn(true);
    when(applicabilityChecker.isApplicable(relatedChange, "")).thenReturn(true);
    when(change.getPatchSetAttribute()).thenReturn(Optional.of(patchSet));
    when(relatedChange.getPatchSetAttribute()).thenReturn(Optional.of(patchSet));
    when(gerritClient.getTopicChanges(change)).thenReturn(topicChanges);

    EventHandlerTypePatchSetReview handler =
        new EventHandlerTypePatchSetReview(
            config,
            changeSetData,
            change,
            reviewer,
            gerritClient,
            coordinator,
            applicabilityChecker,
            false);

    assertEquals(PreprocessResult.OK, handler.preprocessEvent());
    handler.processEvent();

    verify(gerritClient).getTopicChanges(change);
    verify(reviewer).reviewTopic(topicChanges, false);
  }
}
