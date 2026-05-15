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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiMessageItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

@Slf4j
public class AiDataPrompt {
  private final IAiDataPrompt openAiDataPromptHandler;

  public AiDataPrompt(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      GerritClientData gerritClientData,
      Localizer localizer) {
    openAiDataPromptHandler =
        AiPromptFactory.getAiDataPrompt(
            config, changeSetData, change, gerritClientData, localizer);
    log.debug("AiDataPrompt initialized for change: {}", change.getFullChangeId());
  }

  public String buildPrompt() {
    log.debug("Building data prompt for AI.");
    for (int i = 0; i < openAiDataPromptHandler.getCommentProperties().size(); i++) {
      openAiDataPromptHandler.addMessageItem(i);
      log.debug("Added message item to prompt for comment index: {}", i);
    }
    List<AiMessageItem> messageItems = openAiDataPromptHandler.getMessageItems();
    String promptJson = messageItems.isEmpty() ? "" : getGson().toJson(messageItems);
    log.debug("Final AI prompt JSON: {}", promptJson);
    return promptJson;
  }
}
