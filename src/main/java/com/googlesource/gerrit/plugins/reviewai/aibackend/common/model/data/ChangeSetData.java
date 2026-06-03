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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data;

import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
@Data
@Slf4j
public class ChangeSetData {
  @NonNull private Integer aiAccountId;
  private String aiDataPrompt;
  private Integer commentPropertiesSize;
  private ReviewAssistantStage reviewAssistantStage = ReviewAssistantStage.REVIEW_CODE;
  private Boolean forcedStagedReview = false;
  private ReviewScope reviewScope;
  @NonNull private Integer votingMinScore;
  @NonNull private Integer votingMaxScore;

  // Command variables
  private Boolean forcedReview = false;
  private Boolean replyFilterEnabled = true;
  private Boolean debugReviewMode = false;
  private Boolean hideAiReview = false;
  private Boolean hideDynamicConfigMessage = false;
  private Boolean showDynamicConfigMessage = false;
  private String reviewSystemMessage;
  private String reviewNoticeMessage;
  private Set<String> parsedCommands = new HashSet<>();
  private Map<String, Map<String, String>> parsedCommandOptions = new HashMap<>();

  public void clearParsedCommands() {
    parsedCommands.clear();
    parsedCommandOptions.clear();
  }

  public void addParsedCommand(String command, Map<String, String> options) {
    parsedCommands.add(command);
    parsedCommandOptions.put(command, new HashMap<>(options));
  }

  public Boolean hasParsedCommand(String command) {
    return parsedCommands.contains(command);
  }

  public Boolean hasParsedCommandOption(String command, String option, String value) {
    return value.equals(parsedCommandOptions.getOrDefault(command, Map.of()).get(option));
  }

  public Boolean shouldHideAiReview() {
    return hideAiReview && !forcedReview;
  }

  public Boolean shouldRequestAiReview() {
    return reviewSystemMessage == null && !shouldHideAiReview();
  }

  public ChangeSetData copy() {
    ChangeSetData copy = new ChangeSetData(aiAccountId, votingMinScore, votingMaxScore);
    copy.setAiDataPrompt(aiDataPrompt);
    copy.setCommentPropertiesSize(commentPropertiesSize);
    copy.setReviewAssistantStage(reviewAssistantStage);
    copy.setForcedStagedReview(forcedStagedReview);
    copy.setReviewScope(reviewScope);
    copy.setForcedReview(forcedReview);
    copy.setReplyFilterEnabled(replyFilterEnabled);
    copy.setDebugReviewMode(debugReviewMode);
    copy.setHideAiReview(hideAiReview);
    copy.setHideDynamicConfigMessage(hideDynamicConfigMessage);
    copy.setShowDynamicConfigMessage(showDynamicConfigMessage);
    copy.setReviewSystemMessage(reviewSystemMessage);
    copy.setReviewNoticeMessage(reviewNoticeMessage);
    copy.setParsedCommands(new HashSet<>(parsedCommands));
    Map<String, Map<String, String>> copiedParsedCommandOptions = new HashMap<>();
    parsedCommandOptions.forEach(
        (command, options) -> copiedParsedCommandOptions.put(command, new HashMap<>(options)));
    copy.setParsedCommandOptions(copiedParsedCommandOptions);
    return copy;
  }
}
