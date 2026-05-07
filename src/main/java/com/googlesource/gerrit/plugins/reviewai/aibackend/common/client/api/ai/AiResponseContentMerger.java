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
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AiResponseContentMerger {
  private AiResponseContentMerger() {}

  public static AiResponseContent merge(List<AiResponseContent> aiResponseContents) {
    log.debug("Merging responses from different multi-agent stages.");
    AiResponseContent mergedResponse = aiResponseContents.remove(0);
    for (AiResponseContent aiResponseContent : aiResponseContents) {
      List<AiReplyItem> replies = aiResponseContent.getReplies();
      if (replies != null) {
        mergedResponse.getReplies().addAll(replies);
      } else {
        mergedResponse.setMessageContent(aiResponseContent.getMessageContent());
      }
    }
    log.debug("Merged response content: {}", mergedResponse.getMessageContent());
    return mergedResponse;
  }
}
