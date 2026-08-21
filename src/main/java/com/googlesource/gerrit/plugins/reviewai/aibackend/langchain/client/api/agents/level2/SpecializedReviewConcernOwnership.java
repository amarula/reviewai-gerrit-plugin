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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinition;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinitions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Normalizes Level 2 concern ownership independently from source-agent provenance. */
final class SpecializedReviewConcernOwnership {
  private static final String COMMIT_MESSAGE = "COMMIT_MESSAGE";

  private SpecializedReviewConcernOwnership() {}

  static Optional<String> canonicalOwner(String owner) {
    String normalized = SpecializedReviewAgentDefinition.normalizeName(owner);
    if (COMMIT_MESSAGE.equals(normalized)
        || SpecializedReviewAgentDefinitions.findByName(normalized).isPresent()) {
      return Optional.of(normalized);
    }
    return Optional.empty();
  }

  static void normalizeLedger(ReviewConcernLedger ledger) {
    ledger.normalize();
    Map<ConcernReviewerId, List<ReviewConcern>> concernsByReviewer = new LinkedHashMap<>();
    Map<ConcernReviewerId, Set<String>> concernIdsByReviewer = new LinkedHashMap<>();
    for (ReviewerConcerns reviewerConcerns : ledger.getReviewers()) {
      reviewerConcerns.normalize();
      ConcernReviewerId currentReviewer = reviewerConcerns.getReviewer();
      for (ReviewConcern concern : reviewerConcerns.getConcerns()) {
        ConcernReviewerId owner = ownerReviewer(concern, currentReviewer);
        addConcern(concernsByReviewer, concernIdsByReviewer, owner, concern);
      }
    }
    ledger.setReviewers(
        concernsByReviewer.entrySet().stream()
            .map(entry -> reviewerConcerns(entry.getKey(), entry.getValue()))
            .toList());
  }

  static void preserveOwners(
      SpecializedReviewFindings findings, SpecializedReviewFindings ownerSource) {
    findings.normalize();
    ownerSource.normalize();
    Map<String, String> ownersByRawConcernId = new LinkedHashMap<>();
    for (ReviewConcern sourceConcern : ownerSource.getConcerns()) {
      for (String rawConcernId : SpecializedReviewConcernIds.rawConcernIds(sourceConcern)) {
        ownersByRawConcernId.put(rawConcernId, sourceConcern.getOwnerAgent());
      }
    }
    for (ReviewConcern concern : findings.getConcerns()) {
      SpecializedReviewConcernIds.rawConcernIds(concern).stream()
          .map(ownersByRawConcernId::get)
          .filter(owner -> owner != null && !owner.isBlank())
          .findFirst()
          .ifPresent(concern::setOwnerAgent);
    }
  }

  private static ConcernReviewerId ownerReviewer(
      ReviewConcern concern, ConcernReviewerId currentReviewer) {
    if (currentReviewer == null
        || currentReviewer.getKind() != ConcernReviewerId.Kind.SPECIALIZED_AGENT) {
      return currentReviewer;
    }
    Optional<String> owner = canonicalOwner(concern.getOwnerAgent());
    if (owner.isEmpty()) {
      owner = canonicalOwner(concern.getType());
    }
    if (owner.isEmpty()) {
      owner = soleSourceOwner(concern);
    }
    if (owner.isEmpty()) {
      owner = canonicalOwner(currentReviewer.getName());
    }
    if (owner.isEmpty()) {
      return currentReviewer;
    }
    concern.setOwnerAgent(owner.get());
    return new ConcernReviewerId(
        ConcernReviewerId.Kind.SPECIALIZED_AGENT, owner.get());
  }

  private static Optional<String> soleSourceOwner(ReviewConcern concern) {
    Set<String> owners = new LinkedHashSet<>();
    for (ConcernReviewerId reviewer : concern.getReviewers()) {
      if (reviewer != null
          && reviewer.getKind() == ConcernReviewerId.Kind.SPECIALIZED_AGENT) {
        canonicalOwner(reviewer.getName()).ifPresent(owners::add);
      }
    }
    return owners.size() == 1 ? Optional.of(owners.iterator().next()) : Optional.empty();
  }

  private static void addConcern(
      Map<ConcernReviewerId, List<ReviewConcern>> concernsByReviewer,
      Map<ConcernReviewerId, Set<String>> concernIdsByReviewer,
      ConcernReviewerId reviewer,
      ReviewConcern concern) {
    List<ReviewConcern> concerns =
        concernsByReviewer.computeIfAbsent(reviewer, unused -> new ArrayList<>());
    String id = concern.getId();
    if (id == null
        || id.isBlank()
        || concernIdsByReviewer
            .computeIfAbsent(reviewer, unused -> new LinkedHashSet<>())
            .add(id)) {
      concerns.add(concern);
    }
  }

  private static ReviewerConcerns reviewerConcerns(
      ConcernReviewerId reviewer, List<ReviewConcern> concerns) {
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setReviewer(reviewer);
    reviewerConcerns.setConcerns(concerns);
    return reviewerConcerns;
  }
}
