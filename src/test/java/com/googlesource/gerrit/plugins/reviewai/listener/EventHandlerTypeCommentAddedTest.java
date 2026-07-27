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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType.PreprocessResult;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewer;
import org.junit.Before;
import org.junit.Test;

public class EventHandlerTypeCommentAddedTest {
  private static final String EXPRESSION = "label:Verified>=1";

  private Configuration config;
  private ChangeSetData changeSetData;
  private GerritChange change;
  private GerritClient gerritClient;
  private AiReviewApplicabilityChecker applicabilityChecker;
  private EventHandlerTypeCommentAdded handler;
  private CommentAddedEvent event;

  @Before
  public void setUp() {
    config = mock(Configuration.class);
    changeSetData = mock(ChangeSetData.class);
    change = mock(GerritChange.class);
    gerritClient = mock(GerritClient.class);
    applicabilityChecker = mock(AiReviewApplicabilityChecker.class);
    Change eventChange = mock(Change.class);
    Project.NameKey project = Project.nameKey("test/project");
    when(eventChange.getProject()).thenReturn(project);
    when(eventChange.getDest()).thenReturn(BranchNameKey.create(project, "refs/heads/main"));
    when(eventChange.getKey()).thenReturn(Change.key("I0123456789abcdef"));
    event = new CommentAddedEvent(eventChange);

    when(config.getAiReviewApplicableIf()).thenReturn(EXPRESSION);
    when(change.getPatchSetEvent()).thenReturn(event);
    when(gerritClient.getCodeReviewValue(change)).thenReturn(null);
    handler =
        new EventHandlerTypeCommentAdded(
            config,
            changeSetData,
            change,
            mock(PatchSetReviewer.class),
            gerritClient,
            applicabilityChecker,
            false);
  }

  @Test
  public void startsDeferredReviewAfterMatchingApprovalUpdate() {
    event.approvals = Suppliers.ofInstance(new ApprovalAttribute[] {new ApprovalAttribute()});
    when(applicabilityChecker.isApplicable(change, EXPRESSION)).thenReturn(true);

    assertEquals(PreprocessResult.SWITCH_TO_PATCH_SET_CREATED, handler.preprocessEvent());

    verify(changeSetData).setForcedReview(true);
    verify(changeSetData).setDeferredReview(true);
    verify(gerritClient, never()).retrieveLastComments(change, false);
  }

  @Test
  public void doesNotStartDeferredReviewWhenExpressionDoesNotMatch() {
    event.approvals = Suppliers.ofInstance(new ApprovalAttribute[] {new ApprovalAttribute()});

    assertEquals(PreprocessResult.EXIT, handler.preprocessEvent());

    verify(applicabilityChecker).isApplicable(change, EXPRESSION);
    verify(changeSetData, never()).setForcedReview(true);
    verify(changeSetData, never()).setDeferredReview(true);
  }

  @Test
  public void doesNotReevaluateExpressionForPlainComment() {
    when(gerritClient.retrieveLastComments(change, false)).thenReturn(true);

    assertEquals(PreprocessResult.OK, handler.preprocessEvent());

    verify(applicabilityChecker, never()).isApplicable(change, EXPRESSION);
  }

  @Test
  public void existingAiVotePreventsDeferredReview() {
    event.approvals = Suppliers.ofInstance(new ApprovalAttribute[] {new ApprovalAttribute()});
    when(gerritClient.getCodeReviewValue(change)).thenReturn(1);

    assertEquals(PreprocessResult.EXIT, handler.preprocessEvent());

    verify(applicabilityChecker, never()).isApplicable(change, EXPRESSION);
    verify(changeSetData, never()).setForcedReview(true);
    verify(changeSetData, never()).setDeferredReview(true);
  }
}
