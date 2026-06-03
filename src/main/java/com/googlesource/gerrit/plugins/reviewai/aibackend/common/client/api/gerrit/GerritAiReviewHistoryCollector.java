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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account.ReviewAiUser;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        Comparator.comparing(
                AiReviewHistoryInfo.Entry::getUpdated, Comparator.nullsLast(String::compareTo))
            .thenComparing(AiReviewHistoryInfo.Entry::getId, Comparator.nullsLast(String::compareTo)));

    return new AiReviewHistoryInfo(entries);
  }

  public AiReviewHistoryInfo collect(
      Configuration config,
      Localizer localizer,
      int aiAccountId,
      GerritClientData gerritClientData) {
    return collect(config, localizer, aiAccountId, toCommentsByFile(gerritClientData));
  }

  private Map<String, List<GerritComment>> toCommentsByFile(GerritClientData gerritClientData) {
    Map<String, List<GerritComment>> commentsByFile = new HashMap<>();
    if (gerritClientData == null) {
      return commentsByFile;
    }

    Set<String> seenComments = new HashSet<>();
    addCommentsByFile(commentsByFile, seenComments, gerritClientData.getDetailComments());

    CommentData commentData = gerritClientData.getCommentData();
    if (commentData == null) {
      return commentsByFile;
    }
    if (commentData.getPatchSetCommentMap() != null) {
      addCommentsByFile(
          commentsByFile, seenComments, commentData.getPatchSetCommentMap().values());
    }
    if (commentData.getCommentMap() != null) {
      addCommentsByFile(commentsByFile, seenComments, commentData.getCommentMap().values());
    }
    addCommentsByFile(commentsByFile, seenComments, commentData.getCommentProperties());
    return commentsByFile;
  }

  private void addCommentsByFile(
      Map<String, List<GerritComment>> commentsByFile,
      Set<String> seenComments,
      Collection<GerritComment> comments) {
    if (comments == null) {
      return;
    }
    for (GerritComment comment : comments) {
      if (comment == null || !seenComments.add(commentKey(comment))) {
        continue;
      }
      String filename =
          Optional.ofNullable(comment.getFilename()).orElse(Settings.GERRIT_PATCH_SET_FILENAME);
      commentsByFile.computeIfAbsent(filename, key -> new ArrayList<>()).add(comment);
    }
  }

  private String commentKey(GerritComment comment) {
    return Stream.of(comment.getChangeMessageId(), comment.getId())
        .filter(value -> value != null && !value.isBlank())
        .findFirst()
        .orElseGet(
            () ->
                String.join(
                    ":",
                    Optional.ofNullable(comment.getFilename()).orElse(""),
                    Optional.ofNullable(comment.getUpdated()).orElse(comment.getDate()),
                    Optional.ofNullable(comment.getMessage()).orElse("")));
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
    boolean fromAi = ReviewAiUser.matches(comment, aiAccountId);
    boolean systemMessage = fromAi && isSystemMessage(comment, localizer);
    boolean preservedAssistantMessage =
        systemMessage || fromAi && isDynamicConfigurationMessage(comment, localizer);

    ClientMessageCleaner cleaner =
        new ClientMessageCleaner(config, Optional.ofNullable(comment.getMessage()).orElse(""), localizer);
    if (fromAi && !preservedAssistantMessage) {
      cleaner.removeDebugCodeBlocks();
    } else {
      if (!fromAi) {
        cleaner.removeMentions();
      }
    }
    String cleanedMessage = cleaner.getMessage();
    if (preservedAssistantMessage) {
      cleanedMessage = cleanPreservedAssistantMessage(cleanedMessage);
    } else {
      cleaner.removeHeadings();
      cleanedMessage = cleaner.getMessage().trim();
    }
    String reviewScore = fromAi ? getCodeReviewScore(comment) : null;
    if (cleanedMessage.isEmpty() && reviewScore == null) {
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
            reviewScore,
            cleanedMessage));
  }

  private String getCodeReviewScore(GerritComment comment) {
    if (comment.getReviewScore() != null) {
      return comment.getReviewScore();
    }
    return GerritCodeReviewScoreParser.getCodeReviewScore(comment.getMessage());
  }

  private boolean isDirectedToAi(GerritComment comment, int aiAccountId, Pattern botMentionPattern) {
    if (comment == null || comment.getAuthor() == null || comment.isAutogenerated()) {
      return false;
    }
    if (ReviewAiUser.matches(comment.getAuthor(), aiAccountId)) {
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
    if (ReviewAiUser.matches(comment, aiAccountId)) {
      return true;
    }
    return isDirectedToAi(comment, aiAccountId, botMentionPattern);
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

  private String cleanPreservedAssistantMessage(String message) {
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
