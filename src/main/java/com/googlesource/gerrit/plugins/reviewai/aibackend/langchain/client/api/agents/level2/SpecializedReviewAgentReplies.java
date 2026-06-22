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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
class SpecializedReviewAgentReplies {
  private final String agent;
  private final List<SpecializedReviewAgentReply> replies;

  static SpecializedReviewAgentReplies from(String agent, List<AiReplyItem> replies) {
    return new SpecializedReviewAgentReplies(
        agent, replies.stream().map(SpecializedReviewAgentReply::from).toList());
  }

  @Data
  @RequiredArgsConstructor
  static class SpecializedReviewAgentReply {
    private Integer id;
    private final String reply;
    private final Double score;
    private final String filename;
    private final Integer lineNumber;
    private final String codeSnippet;

    private static SpecializedReviewAgentReply from(AiReplyItem reply) {
      SpecializedReviewAgentReply specializedReply =
          new SpecializedReviewAgentReply(
          reply.getReply(),
          reply.getScore(),
          reply.getFilename(),
          reply.getLineNumber(),
          reply.getCodeSnippet());
      specializedReply.setId(reply.getId());
      return specializedReply;
    }
  }
}
