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
import java.util.List;
import lombok.Data;

@Data
public class ReviewConcernLedger {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  @SerializedName("schema_version")
  private int schemaVersion = CURRENT_SCHEMA_VERSION;

  @SerializedName("last_reviewed_commit")
  private String lastReviewedCommit;

  private List<ReviewerConcerns> reviewers = List.of();

  public void normalize() {
    if (lastReviewedCommit != null) {
      lastReviewedCommit = lastReviewedCommit.trim();
      if (lastReviewedCommit.isEmpty()) {
        lastReviewedCommit = null;
      }
    }
    if (reviewers == null) {
      reviewers = List.of();
    }
    reviewers.forEach(ReviewerConcerns::normalize);
  }
}
