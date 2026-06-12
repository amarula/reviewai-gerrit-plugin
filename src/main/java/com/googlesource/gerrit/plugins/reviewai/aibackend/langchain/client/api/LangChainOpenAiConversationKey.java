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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiConversation;

final class LangChainOpenAiConversationKey {
  private LangChainOpenAiConversationKey() {}

  static String from(ChangeSetData changeSetData) {
    if (changeSetData.getReviewAssistantStage() == null) {
      return OpenAiConversation.KEY_CONVERSATION_ID;
    }
    return switch (changeSetData.getReviewAssistantStage()) {
      case REVIEW_CODE, REVIEW_COMMIT_MESSAGE ->
          OpenAiConversation.getMultiAgentConversationKey(changeSetData.getReviewAssistantStage());
      default -> OpenAiConversation.KEY_CONVERSATION_ID;
    };
  }
}
