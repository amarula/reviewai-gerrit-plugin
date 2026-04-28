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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.ClientMessageCleaner;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.CODE_DELIMITER;

@Singleton
@Slf4j
public class GerritAiReviewHistoryCollector {
  private static final Pattern PATCH_SET_PREFIX_PATTERN =
      Pattern.compile(
          "^"
              + Settings.GERRIT_DEFAULT_MESSAGE_PATCH_SET
              + " \\d+:[^\\n]*(?:\\s+\\(\\d+ "
              + Settings.GERRIT_DEFAULT_MESSAGE_COMMENTS
              + "?\\)\\s*)?\\n*",
          Pattern.DOTALL);
  private static final Pattern CODE_REVIEW_SCORE_PATTERN =
      Pattern.compile(
          "^"
              + Settings.GERRIT_DEFAULT_MESSAGE_PATCH_SET
              + " \\d+:[^\\n]*\\bCode-Review([+-]\\d+)\\b");

  public AiReviewHistoryInfo collect(
      Configuration config,
      Localizer localizer,
      int aiAccountId,
      Map<String, List<GerritComment>> inlineComments) {
    Pattern botMentionPattern = getBotMentionPattern(config);
    List<GerritComment> comments = flattenComments(inlineComments);

    List<AiReviewHistoryInfo.Entry> entries = new ArrayList<>();
    for (GerritComment comment : comments) {
      if (!shouldDisplay(comment, aiAccountId, botMentionPattern)) {
        continue;
      }
      addConversationEntry(entries, config, localizer, aiAccountId, comment);
    }

    entries.sort(
        Comparator.comparing(AiReviewHistoryInfo.Entry::getUpdated, Comparator.nullsLast(String::compareTo))
            .thenComparing(AiReviewHistoryInfo.Entry::getId, Comparator.nullsLast(String::compareTo)));

    return new AiReviewHistoryInfo(entries);
  }

  private List<GerritComment> flattenComments(Map<String, List<GerritComment>> inlineComments) {
    List<GerritComment> comments = new ArrayList<>();
    for (Map.Entry<String, List<GerritComment>> entry : inlineComments.entrySet()) {
      for (GerritComment comment : entry.getValue()) {
        if (comment.getFilename() == null) {
          comment.setFilename(entry.getKey());
        }
        comments.add(comment);
      }
    }
    comments.sort(
        Comparator.comparing(this::getUpdated, Comparator.nullsLast(String::compareTo))
            .thenComparing(GerritComment::getId, Comparator.nullsLast(String::compareTo)));
    return comments;
  }

  private void addConversationEntry(
      List<AiReviewHistoryInfo.Entry> entries,
      Configuration config,
      Localizer localizer,
      int aiAccountId,
      GerritComment comment) {
    boolean fromAi = isFromAi(comment, aiAccountId);
    boolean systemMessage = fromAi && isPreservedAssistantMessage(comment, localizer);

    ClientMessageCleaner cleaner =
        new ClientMessageCleaner(config, Optional.ofNullable(comment.getMessage()).orElse(""), localizer);
    if (fromAi && !systemMessage) {
      cleaner.removeDebugCodeBlocks();
    } else {
      if (!fromAi) {
        cleaner.removeMentions();
      }
    }
    String cleanedMessage = cleaner.getMessage();
    if (systemMessage) {
      cleanedMessage = cleanPreservedAssistantMessage(cleanedMessage, localizer);
    } else {
      cleaner.removeHeadings();
      cleanedMessage = cleaner.getMessage().trim();
    }
    if (cleanedMessage.isEmpty()) {
      return;
    }

    entries.add(
        new AiReviewHistoryInfo.Entry(
            comment.getId(),
            fromAi ? Settings.OPENAI_ROLE_ASSISTANT : Settings.OPENAI_ROLE_USER,
            systemMessage,
            getAuthorName(comment),
            getUpdated(comment),
            comment.getPatchSet(),
            toDisplayFilename(comment.getFilename()),
            comment.getLine(),
            fromAi ? getCodeReviewScore(comment) : null,
            cleanedMessage));
  }

  private String getCodeReviewScore(GerritComment comment) {
    if (comment.getReviewScore() != null) {
      return comment.getReviewScore();
    }
    return Optional.ofNullable(comment.getMessage())
        .map(CODE_REVIEW_SCORE_PATTERN::matcher)
        .filter(matcher -> matcher.find())
        .map(matcher -> matcher.group(1))
        .orElse(null);
  }

  private boolean isDirectedToAi(GerritComment comment, int aiAccountId, Pattern botMentionPattern) {
    if (comment == null || comment.getAuthor() == null || comment.isAutogenerated()) {
      return false;
    }
    if (comment.getAuthor().getAccountId() == aiAccountId) {
      return false;
    }
    String message = Optional.ofNullable(comment.getMessage()).orElse("");
    return !message.isBlank() && botMentionPattern.matcher(message).find();
  }

  private boolean shouldDisplay(
      GerritComment comment, int aiAccountId, Pattern botMentionPattern) {
    if (comment == null) {
      return false;
    }
    if (isFromAi(comment, aiAccountId)) {
      return true;
    }
    return isDirectedToAi(comment, aiAccountId, botMentionPattern);
  }

  private boolean isFromAi(GerritComment comment, int aiAccountId) {
    return comment.getAuthor() != null && comment.getAuthor().getAccountId() == aiAccountId;
  }

  private boolean isPreservedAssistantMessage(GerritComment comment, Localizer localizer) {
    return isSystemMessage(comment, localizer) || isDynamicConfigurationMessage(comment, localizer);
  }

  private boolean isSystemMessage(GerritComment comment, Localizer localizer) {
    String message =
        stripPatchSetHeading(Optional.ofNullable(comment.getMessage()).orElse("")).stripLeading();
    String prefix = Optional.ofNullable(localizer.getText("system.message.prefix")).orElse("").trim();
    return !prefix.isEmpty() && message.startsWith(prefix);
  }

  private boolean isDynamicConfigurationMessage(GerritComment comment, Localizer localizer) {
    String message =
        stripPatchSetHeading(Optional.ofNullable(comment.getMessage()).orElse("")).stripLeading();
    String title =
        Optional.ofNullable(localizer.getText("message.dump.dynamic.configuration.title"))
            .orElse("")
            .trim();
    if (title.isEmpty()) {
      return false;
    }
    return message.startsWith(title) || message.startsWith(CODE_DELIMITER + "\n" + title);
  }

  private String stripPatchSetHeading(String message) {
    return PATCH_SET_PREFIX_PATTERN.matcher(message).replaceFirst("");
  }

  private String cleanPreservedAssistantMessage(String message, Localizer localizer) {
    return stripPatchSetHeading(message).trim();
  }

  private Pattern getBotMentionPattern(Configuration config) {
    String escapedUserName = Pattern.quote(config.getGerritUserName());
    String userEmail = config.getGerritUserEmail();
    String userNameOrEmail =
        userEmail.isBlank() ? escapedUserName : escapedUserName + "|" + Pattern.quote(userEmail);
    return Pattern.compile("^(?!>).*?(?:@" + userNameOrEmail + ")\\b", Pattern.MULTILINE);
  }

  private String getAuthorName(GerritComment comment) {
    GerritComment.Author author = comment.getAuthor();
    return Stream.of(author.getDisplayName(), author.getName(), author.getUsername(), author.getEmail())
        .filter(value -> value != null && !value.isBlank())
        .findFirst()
        .orElse("Unknown");
  }

  private String getUpdated(GerritComment comment) {
    return Optional.ofNullable(comment.getUpdated()).orElse(comment.getDate());
  }

  private String toDisplayFilename(String filename) {
    if (Settings.GERRIT_PATCH_SET_FILENAME.equals(filename)) {
      return null;
    }
    return filename;
  }
}
