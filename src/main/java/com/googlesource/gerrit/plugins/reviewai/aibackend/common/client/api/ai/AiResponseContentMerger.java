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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AiResponseContentMerger {
  private AiResponseContentMerger() {}

  public static AiResponseContent merge(List<AiResponseContent> aiResponseContents) {
    log.debug("Merging responses from different multi-agent stages.");
    AiResponseContent mergedResponse = aiResponseContents.remove(0);
    PendingReviewConcernUpdates pendingUpdates = new PendingReviewConcernUpdates();
    pendingUpdates.mergeFrom(mergedResponse.getPendingConcernUpdates());
    for (AiResponseContent aiResponseContent : aiResponseContents) {
      List<AiReplyItem> replies = aiResponseContent.getReplies();
      if (replies != null) {
        mergedResponse.getReplies().addAll(replies);
      } else {
        mergedResponse.setMessageContent(aiResponseContent.getMessageContent());
      }
      pendingUpdates.mergeFrom(aiResponseContent.getPendingConcernUpdates());
    }
    if (!pendingUpdates.isEmpty()) {
      mergedResponse.setPendingConcernUpdates(pendingUpdates);
    }
    log.debug("Merged response content: {}", mergedResponse.getMessageContent());
    return mergedResponse;
  }
}
