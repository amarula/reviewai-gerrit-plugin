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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account.ReviewAiUser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiMessageItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiRequestMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import java.util.stream.Collectors;
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
    int aiAccountId = changeSetData.getAiAccountId();
    commentProperties =
        commentData.getCommentMap().values().stream()
            .filter(c -> !ReviewAiUser.matches(c, aiAccountId))
            .collect(Collectors.toList());
    log.debug("AiDataPromptReview initialized with {} user comment properties ({} total)",
        commentProperties.size(), commentData.getCommentMap().size());
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

  @Override
  public void appendExtraMessageItems() {
    List<AiHistory.AddressedConcern> concerns =
        aiMessageHistory.collectPreviouslyAddressedConcerns();
    if (concerns.isEmpty()) {
      return;
    }

    String template =
        (String) AiPrompt.getJsonPromptValues("prompts")
            .getOrDefault("DEFAULT_AI_PREVIOUSLY_ADDRESSED_CONCERNS_MESSAGE", "");

    int maxConcerns = 10;
    StringBuilder concernsText = new StringBuilder();
    int count = 0;
    for (AiHistory.AddressedConcern concern : concerns) {
      if (concern.isEmpty() || count >= maxConcerns) {
        break;
      }
      count++;
      String location = "";
      if (concern.getFilename() != null) {
        location = concern.getFilename();
        if (concern.getLine() != null) {
          location += " line " + concern.getLine();
        }
      }
      concernsText.append("\nConcern ").append(count).append(": ");
      if (!location.isEmpty()) {
        concernsText.append(location).append("\n");
      } else {
        concernsText.append("\n");
      }
      concernsText.append("- AI noted: \"").append(concern.getAiConcern()).append("\"\n");
      concernsText.append("- User responded: \"").append(concern.getUserResponse()).append("\"\n");
      if (!concern.getAiAcknowledgment().isEmpty()) {
        concernsText.append("- AI acknowledged: \"").append(concern.getAiAcknowledgment()).append("\"\n");
      }
    }

    if (!template.isEmpty()) {
      String message = String.format(template, concernsText.toString());
      AiMessageItem summaryItem = new AiMessageItem();
      summaryItem.setHistory(
          List.of(AiRequestMessage.builder().role("system").content(message).build()));
      messageItems.add(summaryItem);
    }
    log.debug("Added previously addressed concerns summary ({} of {} concerns)", count, concerns.size());
  }
}
