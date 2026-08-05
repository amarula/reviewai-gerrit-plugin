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

package com.googlesource.gerrit.plugins.reviewai.review.topic;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import java.util.Optional;

public class TopicReviewReplyMapper {
  public Optional<AiReplyItem> replyForChange(AiReplyItem replyItem, String topicFilenamePrefix) {
    if (topicFilenamePrefix == null) {
      return Optional.of(replyItem);
    }
    String filename = replyItem.getFilename();
    if (filename == null || filename.isBlank()) {
      return Optional.of(copyWithFilename(replyItem, filename));
    }
    if (!filename.startsWith(topicFilenamePrefix)) {
      return Optional.empty();
    }
    return Optional.of(copyWithFilename(replyItem, filename.substring(topicFilenamePrefix.length())));
  }

  private AiReplyItem copyWithFilename(AiReplyItem replyItem, String filename) {
    return AiReplyItem.builder()
        .id(replyItem.getId())
        .concernId(replyItem.getConcernId())
        .filename(filename)
        .lineNumber(replyItem.getLineNumber())
        .codeSnippet(replyItem.getCodeSnippet())
        .reply(replyItem.getReply())
        .score(replyItem.getScore())
        .relevance(replyItem.getRelevance())
        .repeated(replyItem.isRepeated())
        .duplicated(replyItem.isDuplicated())
        .conflicting(replyItem.isConflicting())
        .sourceAgent(replyItem.getSourceAgent())
        .repetitionReplyId(replyItem.getRepetitionReplyId())
        .repeatedReason(replyItem.getRepeatedReason())
        .duplicatedReason(replyItem.getDuplicatedReason())
        .conflictingReason(replyItem.getConflictingReason())
        .build();
  }
}
