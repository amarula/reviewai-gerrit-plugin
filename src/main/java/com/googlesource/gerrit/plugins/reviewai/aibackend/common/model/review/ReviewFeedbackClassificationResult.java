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

import com.google.gson.annotations.SerializedName;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import java.util.List;
import java.util.Set;
import lombok.Data;

@Data
public class ReviewFeedbackClassificationResult {
  private List<Classification> classifications = List.of();

  @SerializedName("generic_feedback")
  private String genericFeedback;

  @SerializedName("concern_feedback")
  private List<ConcernFeedback> concernFeedback = List.of();

  @SerializedName("disabled_review_scopes")
  private Set<ReviewScope> disabledReviewScopes = Set.of();

  public enum Category {
    GENERIC,
    IRRELEVANT,
    CONCERN
  }

  @Data
  public static class Classification {
    @SerializedName("comment_id")
    private String commentId;

    private Category category;

    @SerializedName("concern_id")
    private String concernId;
  }

  @Data
  public static class ConcernFeedback {
    @SerializedName("concern_id")
    private String concernId;

    private String summary;
  }
}
