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
import java.util.Map;
import lombok.Data;

@Data
public class ReviewFeedbackMemory {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  @SerializedName("schema_version")
  private int schemaVersion = CURRENT_SCHEMA_VERSION;

  @SerializedName("generic_feedback")
  private String genericFeedback;

  @SerializedName("concern_feedback")
  private Map<String, String> concernFeedback = Map.of();
}
