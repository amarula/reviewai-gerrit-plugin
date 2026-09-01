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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPermittedVotingRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernWorkflowInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackClassificationInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
  private Boolean suggestMode = false;
  private String specializedAgentName;
  private String specializedAgentDescription;
  private String specializedAgentInstructions;
  private String specializedAgentCustomInstructions;
  private Boolean specializedAgentReview = false;
  private String reviewAssistantStageConversationSuffix;
  private GerritPermittedVotingRange permittedVotingRange;
  private Boolean deferredReview = false;
  private Boolean aiReviewConditionMet = true;
  private Map<String, GerritConditionLabel> conditionLabels;
  private transient ReviewConcernLedger previousReviewConcernLedger;
  private transient String incrementalPatchSet;
  private transient ConcernWorkflowInput concernWorkflowInput;
  private transient ReviewFeedbackMemory reviewFeedbackMemory;
  private transient ReviewFeedbackClassificationInput reviewFeedbackClassificationInput;
  private transient List<String> pendingReviewFeedbackCommentIds = List.of();
  private transient boolean reviewFeedbackClassified;
  private transient AiRequestCancellation aiRequestCancellation = new AiRequestCancellation();

  // Command variables
  private Boolean forcedReview = false;
  private Boolean forcedTopicReview = false;
  private Boolean replyFilterEnabled = true;
  private Boolean debugReviewMode = false;
  private Boolean hideAiReview = false;
  private Boolean hideDynamicConfigMessage = false;
  private Boolean showDynamicConfigMessage = false;
  private String reviewSystemMessage;
  private String reviewStatusMessage;
  private String reviewNoticeMessage;
  private String reviewRepeatedCommentsMessage;
  private Set<String> parsedCommands = new HashSet<>();
  private Map<String, Map<String, String>> parsedCommandOptions = new HashMap<>();

  public void setReviewSystemMessage(String reviewSystemMessage) {
    this.reviewSystemMessage = reviewSystemMessage;
    this.reviewStatusMessage = null;
  }

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

  public Boolean hasParsedCommand(CommandSet command) {
    return hasParsedCommand(ClientCommandBase.commandName(command));
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

  public ChangeSetData copyForSuggestion() {
    ChangeSetData suggestionData = copy();
    suggestionData.setForcedReview(true);
    suggestionData.setForcedStagedReview(true);
    suggestionData.setSuggestMode(true);
    ReviewScope scope = suggestionData.getReviewScope();
    if (scope == ReviewScope.PATCHSET || scope == ReviewScope.COMMIT_MESSAGE) {
      suggestionData.setReviewAssistantStage(toReviewAssistantStage(scope));
    }
    return suggestionData;
  }

  private ReviewAssistantStage toReviewAssistantStage(ReviewScope scope) {
    return scope == ReviewScope.COMMIT_MESSAGE
        ? ReviewAssistantStage.REVIEW_COMMIT_MESSAGE
        : ReviewAssistantStage.REVIEW_CODE;
  }

  public ChangeSetData copy() {
    ChangeSetData copy = new ChangeSetData(aiAccountId);
    copy.setAiDataPrompt(aiDataPrompt);
    copy.setCommentPropertiesSize(commentPropertiesSize);
    copy.setReviewAssistantStage(reviewAssistantStage);
    copy.setForcedStagedReview(forcedStagedReview);
    copy.setReviewScope(reviewScope);
    copy.setSuggestMode(suggestMode);
    copy.setSpecializedAgentName(specializedAgentName);
    copy.setSpecializedAgentDescription(specializedAgentDescription);
    copy.setSpecializedAgentInstructions(specializedAgentInstructions);
    copy.setSpecializedAgentCustomInstructions(specializedAgentCustomInstructions);
    copy.setSpecializedAgentReview(specializedAgentReview);
    copy.setReviewAssistantStageConversationSuffix(reviewAssistantStageConversationSuffix);
    copy.setPermittedVotingRange(permittedVotingRange);
    copy.setDeferredReview(deferredReview);
    copy.setAiReviewConditionMet(aiReviewConditionMet);
    if (conditionLabels != null) {
      copy.setConditionLabels(new HashMap<>(conditionLabels));
    }
    copy.setPreviousReviewConcernLedger(previousReviewConcernLedger);
    copy.setIncrementalPatchSet(incrementalPatchSet);
    copy.setConcernWorkflowInput(concernWorkflowInput);
    copy.setReviewFeedbackMemory(reviewFeedbackMemory);
    copy.setReviewFeedbackClassificationInput(reviewFeedbackClassificationInput);
    copy.setPendingReviewFeedbackCommentIds(pendingReviewFeedbackCommentIds);
    copy.setReviewFeedbackClassified(reviewFeedbackClassified);
    copy.setAiRequestCancellation(aiRequestCancellation);
    copy.setForcedReview(forcedReview);
    copy.setForcedTopicReview(forcedTopicReview);
    copy.setReplyFilterEnabled(replyFilterEnabled);
    copy.setDebugReviewMode(debugReviewMode);
    copy.setHideAiReview(hideAiReview);
    copy.setHideDynamicConfigMessage(hideDynamicConfigMessage);
    copy.setShowDynamicConfigMessage(showDynamicConfigMessage);
    copy.setReviewSystemMessage(reviewSystemMessage);
    copy.setReviewStatusMessage(reviewStatusMessage);
    copy.setReviewNoticeMessage(reviewNoticeMessage);
    copy.setReviewRepeatedCommentsMessage(reviewRepeatedCommentsMessage);
    copy.setParsedCommands(new HashSet<>(parsedCommands));
    Map<String, Map<String, String>> copiedParsedCommandOptions = new HashMap<>();
    parsedCommandOptions.forEach(
        (command, options) -> copiedParsedCommandOptions.put(command, new HashMap<>(options)));
    copy.setParsedCommandOptions(copiedParsedCommandOptions);
    return copy;
  }
}
