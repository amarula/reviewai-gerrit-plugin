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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SpecializedReviewConcernIds {
  private record RawIdCheck(
      List<String> actual,
      List<String> duplicates,
      List<String> unknown,
      List<String> missing) {
    boolean matches(boolean requireCompleteCoverage) {
      return duplicates.isEmpty()
          && unknown.isEmpty()
          && (!requireCompleteCoverage || missing.isEmpty());
    }
  }

  private SpecializedReviewConcernIds() {}

  static void assignRawConcernIds(
      List<SpecializedReviewFindings.AgentFindings> specializedFindings) {
    String reviewRunPrefix = "raw-" + UUID.randomUUID();
    int index = 1;
    for (SpecializedReviewFindings.AgentFindings agentFindings : specializedFindings) {
      for (ReviewConcern concern : agentFindings.getConcerns()) {
        if (concern.getId() == null || concern.getId().isBlank()) {
          concern.setId(reviewRunPrefix + "-r" + index);
        }
        index++;
      }
    }
  }

  static Set<String> rawConcernIds(
      List<SpecializedReviewFindings.AgentFindings> specializedFindings) {
    Set<String> concernIds = new LinkedHashSet<>();
    specializedFindings.stream()
        .flatMap(agentFindings -> agentFindings.getConcerns().stream())
        .map(ReviewConcern::getId)
        .filter(id -> id != null && !id.isBlank())
        .forEach(concernIds::add);
    return concernIds;
  }

  static List<String> rawConcernIds(ReviewConcern concern) {
    concern.normalize();
    if (!concern.getMergedConcernIds().isEmpty()) {
      return concern.getMergedConcernIds();
    }
    if (concern.getId() != null && !concern.getId().isBlank()) {
      return List.of(concern.getId());
    }
    return List.of();
  }

  static List<String> mergedConcernIds(SpecializedReviewFindings findings) {
    return findings.getConcerns().stream()
        .flatMap(concern -> rawConcernIds(concern).stream())
        .toList();
  }

  static SpecializedReviewFindings currentRunConsolidationOrFallback(
      SpecializedReviewFindings consolidatedFindings,
      List<SpecializedReviewFindings.AgentFindings> specializedFindings,
      Set<String> expectedConcernIds) {
    consolidatedFindings.normalize();
    RawIdCheck rawIdCheck = checkRawIds(consolidatedFindings, expectedConcernIds);
    if (rawIdCheck.matches(true)) {
      log.debug(
          "Level 2 consolidation output matched current raw concern IDs: {}",
          rawIdCheck.actual());
      return consolidatedFindings;
    }

    log.debug(
        "Level 2 consolidation output does not match current raw concern IDs; using deterministic"
            + " pass-through consolidation. expected={}, merged={}, duplicate={}, unknown={},"
            + " missing={}",
        expectedConcernIds,
        rawIdCheck.actual(),
        rawIdCheck.duplicates(),
        rawIdCheck.unknown(),
        rawIdCheck.missing());
    return deterministicConsolidation(specializedFindings);
  }

  static SpecializedReviewFindings currentRunConflictResolutionOrFallback(
      SpecializedReviewFindings conflictResolvedFindings, SpecializedReviewFindings fallbackFindings) {
    conflictResolvedFindings.normalize();
    fallbackFindings.normalize();
    Set<String> expectedConcernIds = new LinkedHashSet<>(mergedConcernIds(fallbackFindings));
    RawIdCheck rawIdCheck = checkRawIds(conflictResolvedFindings, expectedConcernIds);
    if (rawIdCheck.matches(false)) {
      log.debug(
          "Level 2 conflict-resolution output matched current raw concern IDs: {}",
          rawIdCheck.actual());
      return conflictResolvedFindings;
    }

    log.debug(
        "Level 2 conflict-resolution output does not match current raw concern IDs; using"
            + " annotated input as deterministic fallback. expected={}, merged={}, duplicate={},"
            + " unknown={}",
        expectedConcernIds,
        rawIdCheck.actual(),
        rawIdCheck.duplicates(),
        rawIdCheck.unknown());
    return fallbackFindings;
  }

  static ReviewConcern copyConcern(ReviewConcern concern) {
    concern.normalize();
    ReviewConcern copy = new ReviewConcern();
    copy.setId(concern.getId());
    copy.setMergedConcernIds(List.copyOf(concern.getMergedConcernIds()));
    copy.setType(concern.getType());
    copy.setDescription(concern.getDescription());
    copy.setReasoning(concern.getReasoning());
    copy.setPreexisting(concern.getPreexisting());
    copy.setRepeated(concern.getRepeated());
    copy.setPreviousCommentId(concern.getPreviousCommentId());
    copy.setRepeatedReason(concern.getRepeatedReason());
    copy.setStatus(concern.getStatus());
    copy.setStatusReason(concern.getStatusReason());
    copy.setReviewers(List.copyOf(concern.getReviewers()));
    copy.setReply(concern.getReply());
    copy.setScore(concern.getScore());
    copy.setRelevance(concern.getRelevance());
    copy.setLocations(
        concern.getLocations().stream()
            .map(SpecializedReviewConcernIds::copyLocation)
            .toList());
    return copy;
  }

  private static RawIdCheck checkRawIds(
      SpecializedReviewFindings findings, Set<String> expectedConcernIds) {
    List<String> actualConcernIds = mergedConcernIds(findings);
    Set<String> actualConcernIdSet = new LinkedHashSet<>(actualConcernIds);
    Set<String> seen = new LinkedHashSet<>();
    List<String> duplicateConcernIds =
        actualConcernIds.stream().filter(id -> !seen.add(id)).toList();
    List<String> unknownConcernIds =
        actualConcernIds.stream().filter(id -> !expectedConcernIds.contains(id)).toList();
    List<String> missingConcernIds =
        expectedConcernIds.stream().filter(id -> !actualConcernIdSet.contains(id)).toList();
    return new RawIdCheck(
        actualConcernIds, duplicateConcernIds, unknownConcernIds, missingConcernIds);
  }

  private static SpecializedReviewFindings deterministicConsolidation(
      List<SpecializedReviewFindings.AgentFindings> specializedFindings) {
    SpecializedReviewFindings findings = new SpecializedReviewFindings();
    List<ReviewConcern> concerns = new ArrayList<>();
    List<ReviewConcern> dismissedConcerns = new ArrayList<>();
    for (SpecializedReviewFindings.AgentFindings agentFindings : specializedFindings) {
      agentFindings.getConcerns().forEach(concern -> concerns.add(consolidatedCopy(concern)));
      agentFindings
          .getDismissedConcerns()
          .forEach(concern -> dismissedConcerns.add(copyConcern(concern)));
    }
    findings.setConcerns(concerns);
    findings.setDismissedConcerns(dismissedConcerns);
    return findings;
  }

  private static ReviewConcern consolidatedCopy(ReviewConcern concern) {
    ReviewConcern copy = copyConcern(concern);
    if (copy.getId() != null && !copy.getId().isBlank()) {
      copy.setMergedConcernIds(List.of(copy.getId()));
      copy.setId("c-" + copy.getId());
    }
    return copy;
  }

  private static ConcernLocation copyLocation(ConcernLocation location) {
    ConcernLocation copyLocation = new ConcernLocation();
    copyLocation.setFilename(location.getFilename());
    copyLocation.setLineNumber(location.getLineNumber());
    copyLocation.setCodeSnippet(location.getCodeSnippet());
    return copyLocation;
  }
}
