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
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiMessageItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiRequestMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.OPENAI_ROLE_USER;

@Slf4j
public class AiDataPromptRequests extends AiDataPromptBase {
  protected AiMessageItem messageItem;
  protected List<AiRequestMessage> messageHistory;

  public AiDataPromptRequests(
      Configuration config,
      ChangeSetData changeSetData,
      GerritClientData gerritClientData,
      Localizer localizer) {
    super(config, changeSetData, gerritClientData, localizer);
    commentProperties = commentData.getCommentProperties();
    log.debug("AiDataPromptRequests initialized with comment properties.");
  }

  public void addMessageItem(int i) {
    log.debug("Adding message item for comment index: {}", i);
    AiMessageItem messageItem = getMessageItem(i);
    messageItem.setId(i);
    messageItems.add(messageItem);
    log.debug("Added message item: {}", messageItem);
  }

  @Override
  protected AiMessageItem getMessageItem(int i) {
    messageItem = super.getMessageItem(i);
    log.debug("Retrieving extended message item for index: {}", i);
    GerritComment commentProperty = commentProperties.get(i);
    if (shouldUseNonAiConversationHistory()) {
      setRequestFromCommentProperty(messageItem, i);
      setHistory(
          messageItem, aiMessageHistory.retrieveNonAiConversationHistory(commentProperty));
      log.debug("Message item after setting request content: {}", messageItem);
      return messageItem;
    }

    messageHistory = aiMessageHistory.retrieveHistory(commentProperty);
    if (commentProperty.isPatchSetComment()) {
      setRequestFromCommentProperty(messageItem, i);
      removeLastMatchingUserMessage(messageItem.getRequest());
    } else {
      AiRequestMessage request = extractLastUserMessageFromHistory();
      if (request != null) {
        messageItem.setRequest(request.getContent());
      } else {
        setRequestFromCommentProperty(messageItem, i);
      }
    }
    setHistory(messageItem, messageHistory);
    log.debug("Message item after setting request content: {}", messageItem);
    return messageItem;
  }

  protected void setRequestFromCommentProperty(AiMessageItem messageItem, int i) {
    GerritComment gerritComment = commentProperties.get(i);
    String cleanedMessage = aiMessageHistory.getCleanedMessage(gerritComment);
    log.debug("Getting cleaned Message: {}", cleanedMessage);
    messageItem.setRequest(cleanedMessage);
  }

  private AiRequestMessage extractLastUserMessageFromHistory() {
    log.debug("Extracting last user message from history.");
    for (int i = messageHistory.size() - 1; i >= 0; i--) {
      if (OPENAI_ROLE_USER.equals(messageHistory.get(i).getRole())) {
        AiRequestMessage request = messageHistory.remove(i);
        log.debug("Last user message extracted: {}", request);
        return request;
      }
    }
    log.warn(
        "Error extracting request from message history: no user message found in {}",
        messageHistory);
    return null;
  }

  private void removeLastMatchingUserMessage(String content) {
    log.debug("Removing last matching user message from history: {}", content);
    for (int i = messageHistory.size() - 1; i >= 0; i--) {
      AiRequestMessage message = messageHistory.get(i);
      if (OPENAI_ROLE_USER.equals(message.getRole()) && content.equals(message.getContent())) {
        messageHistory.remove(i);
        return;
      }
    }
  }
}
