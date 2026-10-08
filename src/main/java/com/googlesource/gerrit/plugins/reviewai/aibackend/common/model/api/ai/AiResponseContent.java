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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.SuggestedFix;
import java.util.List;
import java.util.Map;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public final class AiResponseContent {
  private List<AiReplyItem> replies;
  private String changeId;
  private transient PendingReviewConcernUpdates pendingConcernUpdates;

  /**
   * Fixes proposed by this run, keyed by the concern each one is meant to resolve.
   *
   * <p>Transient for the same reason as {@link #pendingConcernUpdates}: it is state for the plugin
   * to act on, not something to send to the model. It is also kept out of the published batches on
   * purpose - a suggestion comment carrying a concern id would be bound as the concern's own
   * comment, replacing the link to the thread it answers.
   */
  private transient Map<String, SuggestedFix> suggestedFixes;

  @NonNull private String messageContent;
}
