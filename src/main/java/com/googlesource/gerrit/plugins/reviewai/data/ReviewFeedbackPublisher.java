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

package com.googlesource.gerrit.plugins.reviewai.data;

import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import java.util.Collection;
import java.util.Optional;

public final class ReviewFeedbackPublisher {
  private final ReviewAiDb db;

  @Inject
  public ReviewFeedbackPublisher(ReviewAiDb db) {
    this.db = db;
  }

  public Optional<ReviewFeedbackMemory> load(GerritChange change) {
    return store(change).loadMemory();
  }

  public void enqueue(GerritChange change, Collection<String> commentIds) {
    store(change).enqueue(commentIds);
  }

  public ReviewFeedbackStore.Claim claimPending(GerritChange change) {
    return store(change).claimPending();
  }

  public void complete(
      GerritChange change,
      ReviewFeedbackStore.Claim claim,
      ReviewFeedbackMemory memory) {
    store(change).complete(claim, memory);
  }

  public void release(GerritChange change, ReviewFeedbackStore.Claim claim) {
    store(change).release(claim);
  }

  public void forget(GerritChange change) {
    store(change).forget();
  }

  private ReviewFeedbackStore store(GerritChange change) {
    return new ReviewFeedbackStore(db, change.getFullChangeId());
  }
}
