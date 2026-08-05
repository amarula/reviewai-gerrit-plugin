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

import com.google.gson.annotations.SerializedName;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import java.util.List;
import lombok.Data;

@Data
class SpecializedReviewFindings {
  private List<ReviewConcern> concerns = List.of();

  @SerializedName("dismissed_concerns")
  private List<ReviewConcern> dismissedConcerns = List.of();

  static SpecializedReviewFindings empty() {
    return new SpecializedReviewFindings();
  }

  void normalize() {
    if (concerns == null) {
      concerns = List.of();
    }
    if (dismissedConcerns == null) {
      dismissedConcerns = List.of();
    }
    concerns.forEach(ReviewConcern::normalize);
    dismissedConcerns.forEach(ReviewConcern::normalize);
  }

  @Data
  static class AgentFindings {
    private final String agent;
    private final List<ReviewConcern> concerns;

    @SerializedName("dismissed_concerns")
    private final List<ReviewConcern> dismissedConcerns;

    static AgentFindings from(String agent, SpecializedReviewFindings findings) {
      findings.normalize();
      return new AgentFindings(agent, findings.getConcerns(), findings.getDismissedConcerns());
    }
  }

  @Data
  static class ConsolidationInput {
    @SerializedName("specialized_findings")
    private final List<AgentFindings> specializedFindings;

    @SerializedName("triage_context")
    private final String triageContext;

    static ConsolidationInput from(List<AgentFindings> specializedFindings, String triageContext) {
      return new ConsolidationInput(specializedFindings, triageContext);
    }
  }

  @Data
  static class HistoricalRepetitionInput {
    @SerializedName("raw_concerns")
    private final List<AgentFindings> rawConcerns;

    @SerializedName("past_comments")
    private final List<PastComment> pastComments;

    static HistoricalRepetitionInput from(
        List<AgentFindings> rawConcerns, List<PastComment> pastComments) {
      List<AgentFindings> concernsOnly =
          rawConcerns.stream()
              .map(findings -> new AgentFindings(findings.getAgent(), findings.getConcerns(), List.of()))
              .toList();
      return new HistoricalRepetitionInput(concernsOnly, pastComments);
    }
  }

  @Data
  static class HistoricalRepetitionResult {
    private List<HistoricalRepetitionAnnotation> annotations = List.of();

    void normalize() {
      if (annotations == null) {
        annotations = List.of();
      }
    }
  }

  @Data
  static class HistoricalRepetitionAnnotation {
    @SerializedName("concern_id")
    private String concernId;

    private Boolean repeated;

    @SerializedName("past_comment_id")
    private String pastCommentId;

    private String reason;

    boolean isRepeated() {
      return Boolean.TRUE.equals(repeated);
    }
  }

  @Data
  static class PastComment {
    private final String id;
    private final String reply;
    private final String filename;
    private final Integer lineNumber;
  }

  @Data
  static class ConflictResolutionInput {
    @SerializedName("consolidated_findings")
    private final SpecializedReviewFindings consolidatedFindings;

    static ConflictResolutionInput from(SpecializedReviewFindings consolidatedFindings) {
      consolidatedFindings.normalize();
      return new ConflictResolutionInput(consolidatedFindings);
    }
  }

  @Data
  static class VerificationInput {
    private final String patchset;

    @SerializedName("conflict_resolved_findings")
    private final SpecializedReviewFindings conflictResolvedFindings;

    static VerificationInput from(String patchSet, SpecializedReviewFindings findings) {
      findings.normalize();
      return new VerificationInput(patchSet, findings);
    }
  }
}
