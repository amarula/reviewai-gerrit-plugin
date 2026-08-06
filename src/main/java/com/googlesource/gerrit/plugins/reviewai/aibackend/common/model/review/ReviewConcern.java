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
public class ReviewConcern {
  private String id;
  private ConcernStatus status = ConcernStatus.PRESENT;

  @SerializedName("status_reason")
  private String statusReason;

  private String type;
  private String description;
  private String reasoning;
  private Boolean preexisting;
  private Boolean repeated;

  @SerializedName("repeated_reason")
  private String repeatedReason;

  @SerializedName("merged_concern_ids")
  private List<String> mergedConcernIds = List.of();

  private List<ConcernReviewerId> reviewers = List.of();
  private List<ConcernLocation> locations = List.of();

  // Publication data is retained so a concern can be rendered consistently on later reviews.
  private String reply;
  private Double score;
  private Double relevance;

  @SerializedName(value = "past_comment_id", alternate = "previous_comment_id")
  private String previousCommentId;

  public void normalize() {
    if (status == null) {
      status = ConcernStatus.PRESENT;
    }
    if (mergedConcernIds == null) {
      mergedConcernIds = List.of();
    }
    if (reviewers == null) {
      reviewers = List.of();
    }
    if (locations == null) {
      locations = List.of();
    }
  }

  public ReviewConcern copy() {
    ReviewConcern copy = new ReviewConcern();
    copy.setId(id);
    copy.setStatus(status);
    copy.setStatusReason(statusReason);
    copy.setType(type);
    copy.setDescription(description);
    copy.setReasoning(reasoning);
    copy.setPreexisting(preexisting);
    copy.setRepeated(repeated);
    copy.setRepeatedReason(repeatedReason);
    copy.setMergedConcernIds(List.copyOf(mergedConcernIds));
    copy.setReviewers(List.copyOf(reviewers));
    copy.setLocations(List.copyOf(locations));
    copy.setReply(reply);
    copy.setScore(score);
    copy.setRelevance(relevance);
    copy.setPreviousCommentId(previousCommentId);
    return copy;
  }
}
