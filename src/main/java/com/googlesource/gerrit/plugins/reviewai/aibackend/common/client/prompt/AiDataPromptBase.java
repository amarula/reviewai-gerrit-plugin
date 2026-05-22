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
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderTransport;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.patch.InlineCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.diff.FileDiffProcessed;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiMessageItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiRequestMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Slf4j
public abstract class AiDataPromptBase implements IAiDataPrompt {
  protected final GerritClientData gerritClientData;
  protected final Configuration config;
  protected final HashMap<String, FileDiffProcessed> fileDiffsProcessed;
  protected final CommentData commentData;
  @Getter protected final List<AiMessageItem> messageItems;

  protected AiHistory aiMessageHistory;
  @Getter protected List<GerritComment> commentProperties;

  public AiDataPromptBase(
      Configuration config,
      ChangeSetData changeSetData,
      GerritClientData gerritClientData,
      Localizer localizer) {
    this.config = config;
    this.gerritClientData = gerritClientData;
    fileDiffsProcessed = gerritClientData.getGerritClientPatchSet().getFileDiffsProcessed();
    commentData = gerritClientData.getCommentData();
    aiMessageHistory = new AiHistory(config, changeSetData, gerritClientData, localizer);
    messageItems = new ArrayList<>();
    log.debug("Initialized AiDataPromptBase with file diffs and comment data.");
  }

  public abstract void addMessageItem(int i);

  protected AiMessageItem getMessageItem(int i) {
    log.debug("Creating message item for comment index: {}", i);
    AiMessageItem messageItem = new AiMessageItem();
    GerritComment commentProperty = commentProperties.get(i);
    log.debug("Retrieved comment property for index {}: {}", i, commentProperty);
    if (commentProperty.getLine() != null || commentProperty.getRange() != null) {
      String filename = commentProperty.getFilename();
      FileDiffProcessed fileDiffProcessed = fileDiffsProcessed.get(filename);
      if (fileDiffProcessed == null) {
        log.debug("No file diff processed available for filename: {}", filename);
        return messageItem;
      }
      InlineCode inlineCode = new InlineCode(fileDiffProcessed);
      messageItem.setFilename(filename);
      messageItem.setLineNumber(commentProperty.getLine());
      messageItem.setCodeSnippet(inlineCode.getInlineCode(commentProperty));
      log.debug("Set code snippet for message item: {}", messageItem.getCodeSnippet());
    } else {
      log.debug("No line or range data available for comment at index: {}", i);
    }

    return messageItem;
  }

  protected void setHistory(
      AiMessageItem messageItem, List<AiRequestMessage> messageHistory) {
    if (!messageHistory.isEmpty()) {
      messageItem.setHistory(messageHistory);
      log.debug("Set message history for message item.");
    } else {
      log.debug("No message history to set for message item.");
    }
  }

  protected boolean shouldUseNonAiConversationHistory() {
    return config != null
        && config.getAiProviderTransport() == AiProviderTransport.LANGCHAIN
        && config.getAiProviderType() == AiProviderType.OPENAI;
  }
}
