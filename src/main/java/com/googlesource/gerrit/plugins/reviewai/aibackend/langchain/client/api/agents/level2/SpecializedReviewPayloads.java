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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.unwrapJsonCode;

final class SpecializedReviewPayloads {
  private SpecializedReviewPayloads() {}

  static String buildConsolidationInput(
      List<SpecializedReviewFindings.AgentFindings> specializedFindings) {
    return getGson().toJson(SpecializedReviewFindings.ConsolidationInput.from(specializedFindings));
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

  static void validateHistoricalRepetitionResult(
      SpecializedReviewFindings.HistoricalRepetitionResult result, Set<String> expectedConcernIds) {
    Map<String, SpecializedReviewFindings.HistoricalRepetitionAnnotation> annotationsById =
        new LinkedHashMap<>();
    for (SpecializedReviewFindings.HistoricalRepetitionAnnotation annotation :
        result.getAnnotations()) {
      if (annotation.getConcernId() == null
          || !expectedConcernIds.contains(annotation.getConcernId())
          || annotationsById.putIfAbsent(annotation.getConcernId(), annotation) != null) {
        throw new IllegalStateException("Invalid historical repetition concern ID");
      }
    }
    if (!annotationsById.keySet().equals(expectedConcernIds)) {
      throw new IllegalStateException("Incomplete historical repetition response");
    }
  }
}
