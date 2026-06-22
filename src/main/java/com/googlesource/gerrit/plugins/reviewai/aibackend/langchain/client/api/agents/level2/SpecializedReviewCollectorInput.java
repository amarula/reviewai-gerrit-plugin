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

import com.google.gson.annotations.SerializedName;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;
import java.util.List;
import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
class SpecializedReviewCollectorInput {
  @SerializedName("new_replies")
  private final List<SpecializedReviewAgentReplies> newReplies;

  @SerializedName("past_replies")
  private final List<PastReply> pastReplies;

  static SpecializedReviewCollectorInput from(
      List<SpecializedReviewAgentReplies> newReplies,
      List<AiReviewHistoryInfo.Entry> historyEntries) {
    List<PastReply> pastReplies =
        historyEntries.stream()
            .map(PastReply::from)
            .filter(reply -> reply.id != null)
            .toList();
    return new SpecializedReviewCollectorInput(newReplies, pastReplies);
  }

  @Data
  @RequiredArgsConstructor
  static class PastReply {
    private final String id;
    private final String reply;
    private final String filename;
    private final Integer lineNumber;

    private static PastReply from(AiReviewHistoryInfo.Entry entry) {
      return new PastReply(
          firstNonBlank(entry.getId(), entry.getChangeMessageId()),
          entry.getMessage(),
          entry.getFilename(),
          entry.getLine());
    }

    private static String firstNonBlank(String primary, String fallback) {
      if (primary != null && !primary.isBlank()) {
        return primary;
      }
      return fallback == null || fallback.isBlank() ? null : fallback;
    }
  }
}
