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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import java.util.Optional;

public final class ReviewConcernPublisher {
  private final ReviewAiDb db;

  @Inject
  public ReviewConcernPublisher(ReviewAiDb db) {
    this.db = db;
  }

  public Optional<ReviewConcernLedger> load(GerritChange change) {
    return new ReviewConcernStore(db, change.getFullChangeId()).load();
  }

  public void clear(GerritChange change) {
    new ReviewConcernStore(db, change.getFullChangeId()).clear();
  }

  public void persist(AiResponseContent response, GerritChange change) {
    if (response == null) {
      return;
    }
    PendingReviewConcernUpdates updates = response.getPendingConcernUpdates();
    if (updates == null) {
      return;
    }
    updates
        .get(change.getFullChangeId())
        .ifPresent(
            ledger -> new ReviewConcernStore(db, change.getFullChangeId()).save(ledger));
  }
}
