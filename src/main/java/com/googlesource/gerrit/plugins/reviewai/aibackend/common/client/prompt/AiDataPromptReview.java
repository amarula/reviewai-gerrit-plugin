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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiDataPrompt;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiMessageItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiRequestMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class AiDataPromptReview extends AiDataPromptBase implements IAiDataPrompt {
  public AiDataPromptReview(
      Configuration config,
      ChangeSetData changeSetData,
      GerritClientData gerritClientData,
      Localizer localizer) {
    super(config, changeSetData, gerritClientData, localizer);
    commentProperties = new ArrayList<>(commentData.getCommentMap().values());
    log.debug("AiDataPromptReview initialized with comment properties.");
  }

  @Override
  public void addMessageItem(int i) {
    log.debug("Adding message item for review at index: {}", i);
    AiMessageItem messageItem = getMessageItem(i);
    if (messageItem.getHistory() != null) {
      messageItems.add(messageItem);
      log.debug("Message item added with history: {}", messageItem);
    } else {
      log.debug("Message item not added due to empty history at index: {}", i);
    }
  }

  @Override
  protected AiMessageItem getMessageItem(int i) {
    log.debug("Retrieving message item for review at index: {}", i);
    AiMessageItem messageItem = super.getMessageItem(i);
    List<AiRequestMessage> messageHistory =
        shouldUseNonAiConversationHistory()
            ? aiMessageHistory.retrieveNonAiConversationHistory(commentProperties.get(i), true)
            : aiMessageHistory.retrieveHistory(commentProperties.get(i), true);
    setHistory(messageItem, messageHistory);
    log.debug("Message item populated with history for review: {}", messageItem);
    return messageItem;
  }
}
