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

import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackPublisher;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackStore;
import java.util.List;

public final class ReviewFeedbackLifecycle {
  private final ReviewFeedbackPublisher publisher;

  @Inject
  public ReviewFeedbackLifecycle(ReviewFeedbackPublisher publisher) {
    this.publisher = publisher;
  }

  void reset(ChangeSetData changeSetData) {
    changeSetData.setPendingReviewFeedbackCommentIds(List.of());
    changeSetData.setReviewFeedbackClassified(false);
  }

  void loadMemory(GerritChange change, ChangeSetData changeSetData) {
    changeSetData.setReviewFeedbackMemory(null);
    publisher.load(change).ifPresent(changeSetData::setReviewFeedbackMemory);
  }

  Session begin(GerritChange change, ChangeSetData changeSetData) {
    if (Boolean.TRUE.equals(change.getIsCommentEvent())
        && !Boolean.TRUE.equals(changeSetData.getForcedReview())) {
      return Session.empty();
    }
    ReviewFeedbackStore.Claim claim = publisher.claimPending(change);
    changeSetData.setPendingReviewFeedbackCommentIds(claim.commentIds());
    return claim.isEmpty() ? Session.empty() : new Session(claim);
  }

  void settle(
      GerritChange change,
      ChangeSetData changeSetData,
      Session session,
      boolean reviewSucceeded) {
    if (!session.isActive()) {
      return;
    }
    if (reviewSucceeded && changeSetData.isReviewFeedbackClassified()) {
      publisher.complete(
          change, session.claim, changeSetData.getReviewFeedbackMemory());
    } else {
      publisher.release(change, session.claim);
    }
    session.settled = true;
  }

  void release(
      GerritChange change, Session session, Exception failure) {
    if (!session.isActive()) {
      return;
    }
    try {
      publisher.release(change, session.claim);
      session.settled = true;
    } catch (RuntimeException releaseFailure) {
      failure.addSuppressed(releaseFailure);
    }
  }

  static final class Session {
    private final ReviewFeedbackStore.Claim claim;
    private boolean settled;

    private Session(ReviewFeedbackStore.Claim claim) {
      this.claim = claim;
    }

    private static Session empty() {
      return new Session(null);
    }

    private boolean isActive() {
      return claim != null && !settled;
    }
  }
}
