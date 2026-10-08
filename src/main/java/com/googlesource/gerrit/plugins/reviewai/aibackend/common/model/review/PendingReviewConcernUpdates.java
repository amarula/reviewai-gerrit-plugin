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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class PendingReviewConcernUpdates {
  private final Map<String, ReviewConcernLedger> ledgersByChange = new LinkedHashMap<>();

  public void put(String fullChangeId, ReviewConcernLedger ledger) {
    ledgersByChange.merge(fullChangeId, ledger, PendingReviewConcernUpdates::mergeLedgers);
  }

  public Optional<ReviewConcernLedger> get(String fullChangeId) {
    return Optional.ofNullable(ledgersByChange.get(fullChangeId));
  }

  public Set<String> detachedConcernIds() {
    return ledgersByChange.values().stream()
        .flatMap(ledger -> ledger.getReviewers().stream())
        .flatMap(reviewer -> reviewer.getConcerns().stream())
        .filter(concern -> concern.getStatus() == ConcernStatus.DETACHED)
        .map(ReviewConcern::getId)
        .filter(id -> id != null)
        .collect(java.util.stream.Collectors.toSet());
  }

  /**
   * Replaces a change's ledger outright.
   *
   * <p>Unlike {@link #put}, which merges the two ledgers reviewer by reviewer, this drops whatever
   * was stored before. Use it when the ledger has been rewritten as a whole and the merge semantics
   * would only obscure that.
   */
  public void replace(String fullChangeId, ReviewConcernLedger ledger) {
    ledgersByChange.put(fullChangeId, ledger);
  }

  public void mergeFrom(PendingReviewConcernUpdates updates) {
    if (updates == null) {
      return;
    }
    updates.ledgersByChange.forEach(this::put);
  }

  public boolean isEmpty() {
    return ledgersByChange.isEmpty();
  }

  private static ReviewConcernLedger mergeLedgers(
      ReviewConcernLedger current, ReviewConcernLedger update) {
    current.normalize();
    update.normalize();
    if (current.getSchemaVersion() != update.getSchemaVersion()) {
      throw new IllegalArgumentException("Cannot merge review concern ledger schema versions");
    }
    List<ReviewerConcerns> merged = new ArrayList<>(current.getReviewers());
    for (ReviewerConcerns updateReviewer : update.getReviewers()) {
      merged.removeIf(
          currentReviewer -> currentReviewer.getReviewer().equals(updateReviewer.getReviewer()));
      merged.add(updateReviewer);
    }
    ReviewConcernLedger result = new ReviewConcernLedger();
    result.setSchemaVersion(current.getSchemaVersion());
    result.setReviewers(merged);
    return result;
  }
}
