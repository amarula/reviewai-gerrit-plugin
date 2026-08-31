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

package com.googlesource.gerrit.plugins.reviewai.listener;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Suppliers;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.data.ApprovalAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackPublisher;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType.PreprocessResult;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewer;
import java.util.HashMap;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class EventHandlerTypeCommentAddedTest {
  private static final String EXPRESSION = "label:Verified>=1";

  private Configuration config;
  private ChangeSetData changeSetData;
  private GerritChange change;
  private GerritClient gerritClient;
  private AiReviewApplicabilityChecker applicabilityChecker;
  private ReviewFeedbackPublisher reviewFeedbackPublisher;
  private EventHandlerTypeCommentAdded handler;
  private CommentAddedEvent event;

  @Before
  public void setUp() {
    config = mock(Configuration.class);
    changeSetData = mock(ChangeSetData.class);
    change = mock(GerritChange.class);
    gerritClient = mock(GerritClient.class);
    applicabilityChecker = mock(AiReviewApplicabilityChecker.class);
    reviewFeedbackPublisher = mock(ReviewFeedbackPublisher.class);
    Change eventChange = mock(Change.class);
    Project.NameKey project = Project.nameKey("test/project");
    when(eventChange.getProject()).thenReturn(project);
    when(eventChange.getDest()).thenReturn(BranchNameKey.create(project, "refs/heads/main"));
    when(eventChange.getKey()).thenReturn(Change.key("I0123456789abcdef"));
    event = new CommentAddedEvent(eventChange);

    when(config.getAiReviewApplicableIf()).thenReturn(EXPRESSION);
    when(change.getPatchSetEvent()).thenReturn(event);
    handler =
        new EventHandlerTypeCommentAdded(
            config,
            changeSetData,
            change,
            mock(PatchSetReviewer.class),
            gerritClient,
            applicabilityChecker,
            reviewFeedbackPublisher,
            false);
  }

  @Test
  public void startsDeferredReviewAfterMatchingApprovalUpdate() {
    event.approvals =
        Suppliers.ofInstance(new ApprovalAttribute[] {approval("Verified", "0", "1")});
    when(applicabilityChecker.isApplicable(change, EXPRESSION)).thenReturn(true);

    assertEquals(PreprocessResult.SWITCH_TO_PATCH_SET_CREATED, handler.preprocessEvent());

    verify(changeSetData).setForcedReview(true);
    verify(changeSetData).setDeferredReview(true);
    verify(gerritClient, never()).retrieveComments(change, false);
  }

  @Test
  public void doesNotStartDeferredReviewWhenExpressionDoesNotMatch() {
    event.approvals = Suppliers.ofInstance(new ApprovalAttribute[] {approval("Verified", "0", "1")});

    assertEquals(PreprocessResult.EXIT, handler.preprocessEvent());

    verify(applicabilityChecker).isApplicable(change, EXPRESSION);
    verify(changeSetData, never()).setForcedReview(true);
    verify(changeSetData, never()).setDeferredReview(true);
  }

  @Test
  public void enqueuesAddressedCommentBeforeSwitchingToForcedReview() {
    GerritComment comment = new GerritComment();
    comment.setId("comment-1");
    when(gerritClient.getClientData(change))
        .thenReturn(
            new GerritClientData(
                null,
                List.of(),
                new CommentData(
                    List.of(), List.of(comment), new HashMap<>(), new HashMap<>()),
                0));
    when(changeSetData.getForcedReview()).thenReturn(true);

    assertEquals(
        PreprocessResult.SWITCH_TO_PATCH_SET_CREATED,
        handler.preprocessEvent());

    verify(reviewFeedbackPublisher)
        .enqueue(eq(change), eq(List.of("comment-1")));
  }

  @Test
  public void doesNotReevaluateExpressionForPlainComment() {
    when(gerritClient.retrieveComments(change, false)).thenReturn(true);

    assertEquals(PreprocessResult.OK, handler.preprocessEvent());

    verify(applicabilityChecker, never()).isApplicable(change, EXPRESSION);
  }

  @Test
  public void reloadsPersistedCommentByExactChangeMessageId() {
    String changeMessageId = "change-message-id";
    handler =
        new EventHandlerTypeCommentAdded(
            config,
            changeSetData,
            change,
            mock(PatchSetReviewer.class),
            gerritClient,
            applicabilityChecker,
            reviewFeedbackPublisher,
            false,
            changeMessageId);
    when(gerritClient.retrieveComments(change, false, changeMessageId)).thenReturn(true);

    assertEquals(PreprocessResult.OK, handler.preprocessEvent());

    verify(gerritClient).retrieveComments(change, false, changeMessageId);
    verify(gerritClient, never()).retrieveComments(change, false);
  }

  @Test
  public void existingAiVoteDoesNotPreventReviewAfterConditionLabelChanges() {
    event.approvals = Suppliers.ofInstance(new ApprovalAttribute[] {approval("Verified", "0", "1")});
    when(gerritClient.getCodeReviewValue(change)).thenReturn(1);
    when(applicabilityChecker.isApplicable(change, EXPRESSION)).thenReturn(true);

    assertEquals(PreprocessResult.SWITCH_TO_PATCH_SET_CREATED, handler.preprocessEvent());

    verify(gerritClient, never()).getCodeReviewValue(change);
    verify(changeSetData).setForcedReview(true);
    verify(changeSetData).setDeferredReview(true);
  }

  @Test
  public void doesNotReevaluateExpressionWhenConditionLabelDidNotChange() {
    event.approvals =
        Suppliers.ofInstance(new ApprovalAttribute[] {approval("Verified", null, "1")});

    assertEquals(PreprocessResult.EXIT, handler.preprocessEvent());

    verify(applicabilityChecker, never()).isApplicable(change, EXPRESSION);
    verify(changeSetData, never()).setForcedReview(true);
    verify(changeSetData, never()).setDeferredReview(true);
  }

  @Test
  public void doesNotReevaluateExpressionWhenUnrelatedLabelChanges() {
    event.approvals =
        Suppliers.ofInstance(new ApprovalAttribute[] {approval("Code-Review", "0", "1")});

    assertEquals(PreprocessResult.EXIT, handler.preprocessEvent());

    verify(applicabilityChecker, never()).isApplicable(change, EXPRESSION);
    verify(changeSetData, never()).setForcedReview(true);
    verify(changeSetData, never()).setDeferredReview(true);
  }

  @Test
  public void reevaluatesWhenAnyConditionLabelChanges() {
    String expression = "label:Verified>=1 OR label:Code-Review>=2";
    when(config.getAiReviewApplicableIf()).thenReturn(expression);
    event.approvals =
        Suppliers.ofInstance(new ApprovalAttribute[] {approval("Code-Review", "1", "2")});
    when(applicabilityChecker.isApplicable(change, expression)).thenReturn(true);

    assertEquals(PreprocessResult.SWITCH_TO_PATCH_SET_CREATED, handler.preprocessEvent());

    verify(changeSetData).setForcedReview(true);
    verify(changeSetData).setDeferredReview(true);
  }

  private static ApprovalAttribute approval(String type, String oldValue, String value) {
    ApprovalAttribute approval = new ApprovalAttribute();
    approval.type = type;
    approval.oldValue = oldValue;
    approval.value = value;
    return approval;
  }
}
