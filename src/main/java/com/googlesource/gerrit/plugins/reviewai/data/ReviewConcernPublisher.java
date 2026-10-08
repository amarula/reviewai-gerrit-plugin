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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.SuggestedFix;
import java.util.Map;
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
    persist(response, change, Map.of());
  }

  public void persist(
      AiResponseContent response,
      GerritChange change,
      Map<String, String> publishedCommentIdsByConcern) {
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
            ledger -> {
              bindPublishedCommentIds(ledger, publishedCommentIdsByConcern);
              if (change.getPatchSetRevision() != null && !change.getPatchSetRevision().isBlank()) {
                ledger.setLastReviewedCommit(change.getPatchSetRevision());
              }
              new ReviewConcernStore(db, change.getFullChangeId()).save(ledger);
            });
  }

  /**
   * Records the fixes proposed for a change's concerns, leaving every concern's status alone.
   *
   * <p>Separate from {@link #persist} on purpose: that one rewrites the ledger from a review
   * response, and a suggestion run produces no concern statuses. Writing through it would either do
   * nothing (there are no pending updates) or overwrite what the last review decided.
   *
   * <p>{@code lastReviewedCommit} is deliberately left where it is. The next review looks for the
   * applied fix among the changes since that commit, so advancing it here would hide the very edit
   * being watched for.
   */
  public void recordSuggestedFixes(GerritChange change, Map<String, SuggestedFix> suggestedFixes) {
    if (suggestedFixes == null || suggestedFixes.isEmpty()) {
      return;
    }
    ReviewConcernStore store = new ReviewConcernStore(db, change.getFullChangeId());
    store
        .load()
        .ifPresent(
            ledger -> {
              ledger.normalize();
              if (attachSuggestedFixes(ledger, suggestedFixes)) {
                store.save(ledger);
              }
            });
  }

  private boolean attachSuggestedFixes(
      ReviewConcernLedger ledger, Map<String, SuggestedFix> suggestedFixes) {
    boolean attached = false;
    for (ReviewerConcerns reviewerConcerns : ledger.getReviewers()) {
      for (ReviewConcern concern : reviewerConcerns.getConcerns()) {
        SuggestedFix fix = suggestedFixes.get(concern.getId());
        if (fix != null) {
          concern.setSuggestedFix(fix);
          attached = true;
        }
      }
    }
    return attached;
  }

  private void bindPublishedCommentIds(
      ReviewConcernLedger ledger, Map<String, String> publishedCommentIdsByConcern) {
    if (publishedCommentIdsByConcern == null || publishedCommentIdsByConcern.isEmpty()) {
      return;
    }
    ledger.normalize();
    ledger.getReviewers().stream()
        .flatMap(reviewer -> reviewer.getConcerns().stream())
        .forEach(
            concern ->
                Optional.ofNullable(publishedCommentIdsByConcern.get(concern.getId()))
                    .filter(commentId -> !commentId.isBlank())
                    .ifPresent(concern::setPreviousCommentId));
  }
}
