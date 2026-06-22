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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;
import java.util.List;

final class SpecializedReviewRepetitionCollector extends SpecializedReviewCollectorAgent {
  @Override
  ReviewAssistantStage stage() {
    return ReviewAssistantStage.REVIEW_SPECIALIZED_REPETITION_COLLECTOR;
  }

  @Override
  List<AiReviewHistoryInfo.Entry> selectHistory(
      AiProviderType providerType, List<AiReviewHistoryInfo.Entry> pastReplies) {
    return providerType == AiProviderType.OPENAI ? List.of() : pastReplies;
  }

  @Override
  void merge(AiReplyItem target, AiReplyItem collectorResult) {
    target.setRepeated(collectorResult.isRepeated());
    String repetitionReplyId = collectorResult.getRepetitionReplyId();
    if (!collectorResult.isRepeated()) {
      target.setRepetitionReplyId(null);
      return;
    }
    if (repetitionReplyId == null || repetitionReplyId.isBlank()) {
      throw new IllegalStateException(
          "Repeated reply " + target.getId() + " has no repetition_reply_id");
    }
    target.setRepetitionReplyId(repetitionReplyId);
  }
}
