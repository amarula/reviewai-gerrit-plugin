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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.util.List;

public class DebugCodeBlocksReview extends DebugCodeBlocksComposer {
  private static final String HIDDEN_REPLY = "hidden: %s";

  public DebugCodeBlocksReview(Localizer localizer) {
    super(localizer, "message.debugging.review.title");
  }

  public String getDebugCodeBlock(AiReplyItem replyItem, boolean isHidden) {
    // Keep fields explicit so new model or subclass fields are not disclosed automatically.
    return super.getDebugCodeBlock(
        List.of(
            String.format(HIDDEN_REPLY, isHidden),
            "concernId: " + replyItem.getConcernId(),
            "reply: " + replyItem.getReply(),
            "score: " + replyItem.getScore(),
            "relevance: " + replyItem.getRelevance(),
            "repeated: " + replyItem.isRepeated(),
            "duplicated: " + replyItem.isDuplicated(),
            "conflicting: " + replyItem.isConflicting(),
            "sourceAgent: " + replyItem.getSourceAgent(),
            "repetitionReplyId: " + replyItem.getRepetitionReplyId(),
            "repeatedReason: " + replyItem.getRepeatedReason(),
            "duplicatedReason: " + replyItem.getDuplicatedReason(),
            "conflictingReason: " + replyItem.getConflictingReason()));
  }
}
