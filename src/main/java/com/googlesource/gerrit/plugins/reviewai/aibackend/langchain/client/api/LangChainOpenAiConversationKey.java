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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;

final class LangChainOpenAiConversationKey {
  private LangChainOpenAiConversationKey() {}

  static String from(ChangeSetData changeSetData) {
    return from(changeSetData, null);
  }

  static String from(ChangeSetData changeSetData, GerritChange change) {
    if (isMessageInteraction(changeSetData, change)) {
      return OpenAiConversation.getMessagesConversationKey();
    }
    if (changeSetData.getReviewAssistantStage() == null) {
      return OpenAiConversation.KEY_CONVERSATION_ID;
    }
    return switch (changeSetData.getReviewAssistantStage()) {
      case REVIEW_SPECIALIZED_AGENT ->
          OpenAiConversation.getSpecializedAgentConversationKey(
              changeSetData.getSpecializedAgentName());
      case REVIEW_CODE,
          REVIEW_COMMIT_MESSAGE,
          REVIEW_CONCERNS,
          FIND_NEW_ISSUES,
          REVIEW_SPECIALIZED_TRIAGE,
          REVIEW_SPECIALIZED_CONSOLIDATION,
          REVIEW_SPECIALIZED_HISTORICAL_REPETITION,
          REVIEW_SPECIALIZED_CONFLICT_RESOLUTION,
          REVIEW_SPECIALIZED_VERIFICATION ->
          OpenAiConversation.getMultiAgentConversationKey(
              changeSetData.getReviewAssistantStage(),
              changeSetData.getReviewAssistantStageConversationSuffix());
      default -> OpenAiConversation.KEY_CONVERSATION_ID;
    };
  }

  private static boolean isMessageInteraction(ChangeSetData changeSetData, GerritChange change) {
    return change != null
        && Boolean.TRUE.equals(change.getIsCommentEvent())
        && !Boolean.TRUE.equals(changeSetData.getForcedReview());
  }
}
