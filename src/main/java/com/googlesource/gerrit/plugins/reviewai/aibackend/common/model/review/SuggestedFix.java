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
import lombok.Data;

/**
 * A fix ReviewAI proposed for a concern, and the condition it was meant to establish.
 *
 * <p>Recorded so an applied fix can end its concern instead of being re-raised. Without it a
 * suggestion and the review that follows it are two unrelated judgements of the same problem: the
 * suggestion is written from the review reply's wording, the review from the concern, and nothing
 * connects them - so an author can apply exactly what they were told and still be blocked by it.
 *
 * <p>{@code resolvedWhen} is carried with the fix rather than beside it on the concern, because the
 * two are one statement: a criterion that could drift from the fix it justifies would reintroduce
 * the disagreement this exists to remove.
 */
@Data
public class SuggestedFix {
  /**
   * What the concern would look like once resolved, in the words of the model that proposed the
   * fix. The re-review judges the applied change against this same sentence.
   */
  @SerializedName("resolved_when")
  private String resolvedWhen;

  /** The file the fix was proposed for, so the applied change is looked for in the right place. */
  private String filename;

  /** The replacement code from the suggestion block, as the author would apply it. */
  private String code;
}
