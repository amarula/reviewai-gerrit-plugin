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
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ReviewFeedbackClassificationInput {
  @SerializedName("current_memory")
  private ReviewFeedbackMemory currentMemory;

  private List<ReviewConcern> concerns;
  private List<Comment> comments;

  @Data
  @AllArgsConstructor
  public static class Comment {
    @SerializedName("target_comment")
    private TargetComment targetComment;

    @SerializedName("thread_concern_id")
    private String threadConcernId;

    @SerializedName("thread_context")
    private List<ThreadMessage> threadContext;
  }

  @Data
  @AllArgsConstructor
  public static class TargetComment {
    private String id;
    private String message;
    private String filename;
    private Integer line;
  }

  @Data
  @AllArgsConstructor
  public static class ThreadMessage {
    private String id;
    private String role;
    private String message;
  }
}
