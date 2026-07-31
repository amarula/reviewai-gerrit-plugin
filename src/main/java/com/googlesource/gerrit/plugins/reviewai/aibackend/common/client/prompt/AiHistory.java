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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.ClientMessageCleaner;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiRequestMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public class AiHistory extends AiComment {
  private static final int MAX_PREVIOUSLY_ADDRESSED_CONCERNS = 10;
  private final Set<String> messagesExcludedFromHistory;
  private final AiHistoryMessageFilter messageFilter;
  @Getter private final HashMap<String, GerritComment> commentMap;
  private final HashMap<String, GerritComment> patchSetCommentMap;
  private final Set<String> patchSetCommentAdded;
  private final List<GerritComment> patchSetComments;
  private final int revisionBase;
  private final Localizer localizer;
  private final String forgetThreadCutoff;

  private boolean filterActive;
  private boolean excludeAiConversationMessages;

  public AiHistory(
      Configuration config,
      ChangeSetData changeSetData,
      GerritClientData gerritClientData,
      Localizer localizer) {
    super(config, changeSetData, localizer);
    this.localizer = localizer;
    CommentData commentData = gerritClientData.getCommentData();
    messagesExcludedFromHistory =
        Set.of(Settings.GERRIT_DEFAULT_MESSAGE_DONE, localizer.getText("message.empty.review"));
    messageFilter = new AiHistoryMessageFilter();
    commentMap = commentData.getCommentMap();
    patchSetCommentMap = commentData.getPatchSetCommentMap();
    patchSetComments = retrievePatchSetComments(gerritClientData);
    revisionBase = gerritClientData.getOneBasedRevisionBase();
    patchSetCommentAdded = new HashSet<>();
    forgetThreadCutoff = findForgetThreadCutoff();
    log.debug("AiHistory initialized with comments and revision base: {}", revisionBase);
  }

  public List<AiRequestMessage> retrieveHistory(
      GerritComment commentProperty, boolean filterActive) {
    this.filterActive = filterActive;
    log.debug("Retrieving history for commentProperty with filterActive={}", filterActive);
    if (commentProperty.isPatchSetComment()) {
      return retrievePatchSetMessageHistory();
    } else {
      log.debug("Retrieving history for comment: {}", commentProperty);
      return retrieveMessageHistory(commentProperty);
    }
  }

  public List<AiRequestMessage> retrieveHistory(GerritComment commentProperty) {
    return retrieveHistory(commentProperty, false);
  }

  public List<AiRequestMessage> retrieveNonAiConversationHistory(
      GerritComment commentProperty, boolean filterActive) {
    boolean previousExcludeAiConversationMessages = excludeAiConversationMessages;
    excludeAiConversationMessages = true;
    try {
      return retrieveHistory(commentProperty, filterActive);
    } finally {
      excludeAiConversationMessages = previousExcludeAiConversationMessages;
    }
  }

  public List<AiRequestMessage> retrieveNonAiConversationHistory(GerritComment commentProperty) {
    return retrieveNonAiConversationHistory(commentProperty, false);
  }

  private List<GerritComment> retrievePatchSetComments(GerritClientData gerritClientData) {
    List<GerritComment> detailComments = gerritClientData.getDetailComments();
    // Normalize detailComments by setting the `update` field to match `date`
    detailComments.forEach(record -> record.setUpdated(record.getDate()));
    // Join the comments from patchSetCommentMap with detailComments
    List<GerritComment> patchSetComments =
        Stream.concat(patchSetCommentMap.values().stream(), detailComments.stream())
            .collect(Collectors.toList());
    sortPatchSetComments(patchSetComments);
    log.debug("Patch set comments sorted by update datetime: {}", patchSetComments);
    return patchSetComments;
  }

  private void sortPatchSetComments(List<GerritComment> patchSetComments) {
    Comparator<GerritComment> byDateUpdated =
        (GerritComment o1, GerritComment o2) -> {
          String dateTime1 = o1.getUpdated();
          String dateTime2 = o2.getUpdated();
          if (dateTime1 == null && dateTime2 == null) return 0;
          if (dateTime1 == null) return 1;
          if (dateTime2 == null) return -1;

          return dateTime1.compareTo(dateTime2);
        };
    patchSetComments.sort(byDateUpdated);
  }

  private String getRoleFromComment(GerritComment currentComment) {
    return isFromAssistant(currentComment)
        ? Settings.OPENAI_ROLE_ASSISTANT
        : Settings.OPENAI_ROLE_USER;
  }

  private List<AiRequestMessage> retrieveMessageHistory(GerritComment currentComment) {
    List<AiRequestMessage> messageHistory = new ArrayList<>();
    log.debug("Retrieving message history for currentComment: {}", currentComment);
    while (currentComment != null) {
      log.debug("Processing comment: {}", currentComment);
      addMessageToHistory(messageHistory, currentComment);
      currentComment = commentMap.get(currentComment.getInReplyTo());
    }
    // Reverse the history sequence so that the oldest message appears first and the newest message
    // is last
    Collections.reverse(messageHistory);
    log.debug("Final message history: {}", messageHistory);
    return messageHistory;
  }

  private List<AiRequestMessage> retrievePatchSetMessageHistory() {
    List<AiRequestMessage> messageHistory = new ArrayList<>();
    log.debug("Retrieving patch set message history.");
    for (GerritComment patchSetComment : patchSetComments) {
      if (patchSetComment.isAutogenerated()) {
        continue;
      }
      if (!isFromAssistant(patchSetComment)) {
        GerritComment patchSetLevelMessage = patchSetCommentMap.get(patchSetComment.getId());
        if (patchSetLevelMessage != null) {
          patchSetComment = patchSetLevelMessage;
        }
      }
      addMessageToHistory(messageHistory, patchSetComment);
    }
    log.debug("Final patch set message history: {}", messageHistory);
    return messageHistory;
  }

  private boolean isInactiveComment(GerritComment comment) {
    boolean isInactive =
        config.getIgnoreResolvedAiComments() && isFromAssistant(comment) && comment.isResolved()
            || config.getIgnoreOutdatedInlineComments()
                && comment.getOneBasedPatchSet() != revisionBase
                && !comment.isPatchSetComment();
    log.debug("Checking if comment is inactive: {}", isInactive);
    return isInactive;
  }

  private String findForgetThreadCutoff() {
    String cutoff =
        Stream.concat(commentMap.values().stream(), patchSetComments.stream())
            .filter(this::isForgetThreadCommand)
            .map(this::getCommentTimestamp)
            .filter(Objects::nonNull)
            .max(String::compareTo)
            .orElse(null);
    log.debug("Last /forget_thread cutoff: {}", cutoff);
    return cutoff;
  }

  private boolean isForgetThreadCommand(GerritComment comment) {
    if (comment == null || comment.getMessage() == null || isFromAssistant(comment)) {
      return false;
    }
    String normalizedMessage =
        new ClientMessageCleaner(config, comment.getMessage(), localizer)
            .removeHeadings()
            .removeMentions()
            .getMessage()
            .trim();
    // Avoid the regex scan for the common case where the command name is not present at all.
    if (!normalizedMessage.contains(ClientCommandBase.commandName(CommandSet.FORGET_THREAD))) {
      return false;
    }
    Matcher commandMatcher = ClientCommandBase.COMMAND_PATTERN.matcher(normalizedMessage);
    while (commandMatcher.find()) {
      if (ClientCommandBase.commandName(CommandSet.FORGET_THREAD).equals(commandMatcher.group(1))) {
        return true;
      }
    }
    return false;
  }

  private String getCommentTimestamp(GerritComment comment) {
    if (comment == null) {
      return null;
    }
    return comment.getUpdated() != null ? comment.getUpdated() : comment.getDate();
  }

  private boolean isBeforeOrAtForgetThreadCutoff(GerritComment comment) {
    String timestamp = getCommentTimestamp(comment);
    return forgetThreadCutoff != null
        && timestamp != null
        && timestamp.compareTo(forgetThreadCutoff) <= 0;
  }

  private void addMessageToHistory(
      List<AiRequestMessage> messageHistory, GerritComment comment) {
    log.debug("Adding message to history - comment: {}", comment);
    if (excludeAiConversationMessages && isAiConversationMessage(comment)) {
      log.debug("Message not added to history because it is part of the AI conversation.");
      return;
    }
    String messageContent = getCleanedMessage(comment);
    log.debug("Cleaned message content: {}", messageContent);
    boolean shouldNotProcessComment =
        messageContent.isEmpty()
            || messagesExcludedFromHistory.contains(messageContent)
            || !messageFilter.shouldIncludeMessage(messageContent)
            || patchSetCommentAdded.contains(messageContent)
            || isBeforeOrAtForgetThreadCutoff(comment)
            || filterActive && isInactiveComment(comment);

    if (shouldNotProcessComment) {
      log.debug(
          "Message not added to history - messagesExcludedFromHistory: {} - patchSetCommentAdded: {} - "
              + "beforeForgetThreadCutoff: {} - isInactiveComment: {}",
          messagesExcludedFromHistory.contains(messageContent),
          patchSetCommentAdded.contains(messageContent),
          isBeforeOrAtForgetThreadCutoff(comment),
          isInactiveComment(comment));
      return;
    }
    patchSetCommentAdded.add(messageContent);

    AiRequestMessage message =
        AiRequestMessage.builder()
            .role(getRoleFromComment(comment))
            .content(messageContent)
            .build();
    log.debug("Message added to history: {}", message);
    messageHistory.add(message);
  }

  private boolean isAiConversationMessage(GerritComment comment) {
    return isFromAssistant(comment) || isAddressedToAssistant(comment) || isReplyToAssistant(comment);
  }

  private boolean isAddressedToAssistant(GerritComment comment) {
    if (comment == null || comment.getMessage() == null) {
      return false;
    }
    ClientMessageCleaner cleaner = new ClientMessageCleaner(config, comment.getMessage(), localizer);
    boolean hasMention =
        !cleaner.removeMentions().getMessage().trim().equals(comment.getMessage().trim());
    return hasMention || ClientCommandBase.COMMAND_PATTERN.matcher(comment.getMessage()).find();
  }

  private boolean isReplyToAssistant(GerritComment comment) {
    if (comment == null || comment.getInReplyTo() == null) {
      return false;
    }
    GerritComment parent = commentMap.get(comment.getInReplyTo());
    return parent != null && isFromAssistant(parent);
  }

  /**
   * Collects inline comment threads where the AI review concern was engaged with by the user,
   * indicating the concern has been previously addressed.
   */
  public List<AddressedConcern> collectPreviouslyAddressedConcerns() {
    List<AddressedConcern> concerns = new ArrayList<>();
    Set<String> processedRootIds = new HashSet<>();

    for (GerritComment comment : commentMap.values()) {
      if (!isFromAssistant(comment) || comment.isAutogenerated()) {
        continue;
      }
      GerritComment root = comment;
      while (root.getInReplyTo() != null && commentMap.containsKey(root.getInReplyTo())) {
        root = commentMap.get(root.getInReplyTo());
      }
      String rootId = root.getId();
      if (rootId == null || !processedRootIds.add(rootId)) {
        continue;
      }
      List<GerritComment> thread = collectThreadReplies(root);
      boolean hasUserReply = thread.stream().anyMatch(c -> !isFromAssistant(c));
      if (hasUserReply) {
        AddressedConcern concern = buildAddressedConcern(root, thread);
        if (concern != null) {
          concerns.add(concern);
        }
      }
    }

    // Sort by most recent first, limit to MAX
    int totalBeforeLimit = concerns.size();
    concerns.sort((a, b) -> b.updated.compareTo(a.updated));
    if (concerns.size() > MAX_PREVIOUSLY_ADDRESSED_CONCERNS) {
      concerns = concerns.subList(0, MAX_PREVIOUSLY_ADDRESSED_CONCERNS);
    }

    log.debug("Collected {} previously addressed concerns ({} total before limit)",
        concerns.size(), totalBeforeLimit);
    return concerns;
  }

  private List<GerritComment> collectThreadReplies(GerritComment root) {
    List<GerritComment> thread = new ArrayList<>();
    thread.add(root);
    for (GerritComment comment : commentMap.values()) {
      GerritComment cursor = comment;
      while (cursor != null && !cursor.getId().equals(root.getId())) {
        String parentId = cursor.getInReplyTo();
        cursor = parentId != null ? commentMap.get(parentId) : null;
      }
      if (cursor != null && !comment.getId().equals(root.getId())) {
        thread.add(comment);
      }
    }
    return thread;
  }

  private AddressedConcern buildAddressedConcern(
      GerritComment rootComment, List<GerritComment> thread) {
    String aiConcern = summarizeComment(getCleanedMessage(rootComment));

    // First pass: find the user response
    String userResponse = null;
    for (GerritComment comment : thread) {
      if (!isFromAssistant(comment) && !comment.getId().equals(rootComment.getId())) {
        userResponse = summarizeComment(getCleanedMessage(comment));
        break;
      }
    }

    // Second pass: find an AI reply that comes after the user response
    String aiAcknowledgment = null;
    for (GerritComment comment : thread) {
      if (isFromAssistant(comment) && userResponse != null
          && !comment.getId().equals(rootComment.getId())) {
        aiAcknowledgment = summarizeComment(getCleanedMessage(comment));
      }
    }

    if (aiConcern.isEmpty() || userResponse == null || userResponse.isEmpty()) {
      return null;
    }

    String updated = rootComment.getUpdated() != null
        ? rootComment.getUpdated()
        : rootComment.getDate();
    return new AddressedConcern(
        rootComment.getFilename(),
        rootComment.getLine(),
        aiConcern,
        userResponse,
        aiAcknowledgment != null ? aiAcknowledgment : "",
        updated);
  }

  private static String summarizeComment(String message) {
    if (message == null || message.isBlank()) {
      return "";
    }
    for (String line : message.split("\n")) {
      String trimmed = line.trim();
      if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
        if (trimmed.length() > 200) {
          trimmed = trimmed.substring(0, 197) + "...";
        }
        return trimmed;
      }
    }
    return "";
  }

  public static class AddressedConcern {
    final String updated;
    private final String filename;
    private final Integer line;
    private final String aiConcern;
    private final String userResponse;
    private final String aiAcknowledgment;

    public AddressedConcern(
        String filename,
        Integer line,
        String aiConcern,
        String userResponse,
        String aiAcknowledgment,
        String updated) {
      this.filename = filename;
      this.line = line;
      this.aiConcern = aiConcern;
      this.userResponse = userResponse;
      this.aiAcknowledgment = aiAcknowledgment;
      this.updated = updated != null ? updated : "";
    }

    public String getFilename() { return filename; }
    public Integer getLine() { return line; }
    public String getAiConcern() { return aiConcern; }
    public String getUserResponse() { return userResponse; }
    public String getAiAcknowledgment() { return aiAcknowledgment; }
    public boolean isEmpty() { return aiConcern.isEmpty() || userResponse.isEmpty(); }
  }
}
