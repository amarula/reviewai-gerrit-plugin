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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.IdentifiedUser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackPublisher;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackStore;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiRole;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiRoleResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

public class ReviewFeedbackLifecycleTest {
  @Test
  public void authorizesModeratorActionsAgainstOriginalFeedbackAuthor() {
    ReviewFeedbackPublisher publisher = mock(ReviewFeedbackPublisher.class);
    Configuration config = mock(Configuration.class);
    IdentifiedUser.GenericFactory identifiedUserFactory = mock(IdentifiedUser.GenericFactory.class);
    IdentifiedUser feedbackAuthor = mock(IdentifiedUser.class);
    AiRoleResolver roleResolver = mock(AiRoleResolver.class);
    GerritChange change = mock(GerritChange.class);
    Project.NameKey project = Project.nameKey("test/project");
    Change.Id changeId = Change.id(7);
    when(change.getChangeNumber()).thenReturn(Optional.of(7));
    when(change.getProjectNameKey()).thenReturn(project);
    when(identifiedUserFactory.create(Account.id(42))).thenReturn(feedbackAuthor);
    when(roleResolver.resolve(config, feedbackAuthor, project, changeId))
        .thenReturn(AiRole.MODERATOR);
    when(publisher.claimPending(change))
        .thenReturn(
            new ReviewFeedbackStore.Claim("claim", List.of("comment-1"), Map.of("comment-1", 42)));
    ChangeSetData changeSetData = new ChangeSetData(1);
    ReviewFeedbackLifecycle lifecycle =
        new ReviewFeedbackLifecycle(publisher, config, identifiedUserFactory, roleResolver);

    lifecycle.begin(change, changeSetData);

    assertEquals(
        Set.of("comment-1"), changeSetData.getReviewFeedbackDismissalAuthorizedCommentIds());
    assertEquals(Set.of("comment-1"), changeSetData.getReviewFeedbackControlAuthorizedCommentIds());
  }

  @Test
  public void ordinaryFeedbackAuthorCannotAuthorizeModeratorActions() {
    ReviewFeedbackPublisher publisher = mock(ReviewFeedbackPublisher.class);
    Configuration config = mock(Configuration.class);
    IdentifiedUser.GenericFactory identifiedUserFactory = mock(IdentifiedUser.GenericFactory.class);
    IdentifiedUser feedbackAuthor = mock(IdentifiedUser.class);
    AiRoleResolver roleResolver = mock(AiRoleResolver.class);
    GerritChange change = mock(GerritChange.class);
    Project.NameKey project = Project.nameKey("test/project");
    Change.Id changeId = Change.id(7);
    when(change.getChangeNumber()).thenReturn(Optional.of(7));
    when(change.getProjectNameKey()).thenReturn(project);
    when(identifiedUserFactory.create(Account.id(42))).thenReturn(feedbackAuthor);
    when(roleResolver.resolve(config, feedbackAuthor, project, changeId)).thenReturn(AiRole.USER);
    when(publisher.claimPending(change))
        .thenReturn(
            new ReviewFeedbackStore.Claim("claim", List.of("comment-1"), Map.of("comment-1", 42)));
    ChangeSetData changeSetData = new ChangeSetData(1);
    ReviewFeedbackLifecycle lifecycle =
        new ReviewFeedbackLifecycle(publisher, config, identifiedUserFactory, roleResolver);

    lifecycle.begin(change, changeSetData);

    assertEquals(Set.of(), changeSetData.getReviewFeedbackDismissalAuthorizedCommentIds());
    assertEquals(Set.of(), changeSetData.getReviewFeedbackControlAuthorizedCommentIds());
  }
}
