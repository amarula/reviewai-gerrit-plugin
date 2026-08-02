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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.unwrapJsonCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SpecializedReviewPayloads {
  private record HistoricalRepetitionIdCheck(
      List<String> actual,
      List<String> duplicates,
      List<String> unknown,
      List<String> missing) {
    boolean matches() {
      return duplicates.isEmpty() && unknown.isEmpty() && missing.isEmpty();
    }
  }

  private SpecializedReviewPayloads() {}

  static String buildConsolidationInput(
      List<SpecializedReviewFindings.AgentFindings> specializedFindings, String triageContext) {
    return getGson()
        .toJson(
            SpecializedReviewFindings.ConsolidationInput.from(
                specializedFindings, triageContext));
  }

  static String buildHistoricalRepetitionInput(
      List<SpecializedReviewFindings.AgentFindings> specializedFindings,
      List<SpecializedReviewFindings.PastComment> pastComments) {
    return getGson()
        .toJson(
            SpecializedReviewFindings.HistoricalRepetitionInput.from(
                specializedFindings, pastComments));
  }

  static String buildConflictResolutionInput(SpecializedReviewFindings consolidatedFindings) {
    return getGson()
        .toJson(SpecializedReviewFindings.ConflictResolutionInput.from(consolidatedFindings));
  }

  static String buildVerificationInput(String patchSet, SpecializedReviewFindings findings) {
    return getGson().toJson(SpecializedReviewFindings.VerificationInput.from(patchSet, findings));
  }

  static SpecializedReviewFindings parseFindingsResponse(String responseText) {
    SpecializedReviewFindings findings =
        getGson().fromJson(unwrapJsonCode(responseText), SpecializedReviewFindings.class);
    if (findings == null) {
      return SpecializedReviewFindings.empty();
    }
    findings.normalize();
    return findings;
  }

  static SpecializedReviewFindings.HistoricalRepetitionResult parseHistoricalRepetitionResponse(
      String responseText) {
    SpecializedReviewFindings.HistoricalRepetitionResult result =
        getGson()
            .fromJson(
                unwrapJsonCode(responseText),
                SpecializedReviewFindings.HistoricalRepetitionResult.class);
    if (result == null) {
      result = new SpecializedReviewFindings.HistoricalRepetitionResult();
    }
    result.normalize();
    return result;
  }

  static SpecializedReviewFindings.HistoricalRepetitionResult
      currentRunHistoricalRepetitionOrFallback(
          SpecializedReviewFindings.HistoricalRepetitionResult result,
          Set<String> expectedConcernIds) {
    result.normalize();
    HistoricalRepetitionIdCheck idCheck = checkHistoricalRepetitionIds(result, expectedConcernIds);
    if (idCheck.matches()) {
      log.debug(
          "Level 2 historical repetition output matched current raw concern IDs: {}",
          idCheck.actual());
      return result;
    }

    log.debug(
        "Level 2 historical repetition output does not match current raw concern IDs; using"
            + " deterministic non-repeated fallback. expected={}, actual={}, duplicate={},"
            + " unknown={}, missing={}",
        expectedConcernIds,
        idCheck.actual(),
        idCheck.duplicates(),
        idCheck.unknown(),
        idCheck.missing());
    return nonRepeatedHistoricalRepetitionResult(expectedConcernIds);
  }

  static void validateHistoricalRepetitionResult(
      SpecializedReviewFindings.HistoricalRepetitionResult result, Set<String> expectedConcernIds) {
    result.normalize();
    HistoricalRepetitionIdCheck idCheck = checkHistoricalRepetitionIds(result, expectedConcernIds);
    if (idCheck.matches()) {
      return;
    }
    if (!idCheck.duplicates().isEmpty() || !idCheck.unknown().isEmpty()) {
      throw new IllegalStateException("Invalid historical repetition concern ID");
    }
    throw new IllegalStateException("Incomplete historical repetition response");
  }

  private static HistoricalRepetitionIdCheck checkHistoricalRepetitionIds(
      SpecializedReviewFindings.HistoricalRepetitionResult result, Set<String> expectedConcernIds) {
    List<String> actualConcernIds = new ArrayList<>();
    List<String> duplicateConcernIds = new ArrayList<>();
    List<String> unknownConcernIds = new ArrayList<>();
    Map<String, SpecializedReviewFindings.HistoricalRepetitionAnnotation> annotationsById =
        new LinkedHashMap<>();
    for (SpecializedReviewFindings.HistoricalRepetitionAnnotation annotation :
        result.getAnnotations()) {
      String concernId = annotation.getConcernId();
      actualConcernIds.add(concernId);
      if (concernId == null || !expectedConcernIds.contains(concernId)) {
        unknownConcernIds.add(concernId);
      }
      if (concernId == null || annotationsById.putIfAbsent(concernId, annotation) != null) {
        duplicateConcernIds.add(concernId);
      }
    }
    List<String> missingConcernIds =
        expectedConcernIds.stream().filter(id -> !annotationsById.containsKey(id)).toList();
    return new HistoricalRepetitionIdCheck(
        actualConcernIds, duplicateConcernIds, unknownConcernIds, missingConcernIds);
  }

  private static SpecializedReviewFindings.HistoricalRepetitionResult
      nonRepeatedHistoricalRepetitionResult(Set<String> expectedConcernIds) {
    SpecializedReviewFindings.HistoricalRepetitionResult result =
        new SpecializedReviewFindings.HistoricalRepetitionResult();
    result.setAnnotations(
        expectedConcernIds.stream()
            .map(SpecializedReviewPayloads::nonRepeatedAnnotation)
            .toList());
    return result;
  }

  private static SpecializedReviewFindings.HistoricalRepetitionAnnotation nonRepeatedAnnotation(
      String concernId) {
    SpecializedReviewFindings.HistoricalRepetitionAnnotation annotation =
        new SpecializedReviewFindings.HistoricalRepetitionAnnotation();
    annotation.setConcernId(concernId);
    annotation.setRepeated(false);
    annotation.setPastCommentId("");
    annotation.setReason("");
    return annotation;
  }
}
