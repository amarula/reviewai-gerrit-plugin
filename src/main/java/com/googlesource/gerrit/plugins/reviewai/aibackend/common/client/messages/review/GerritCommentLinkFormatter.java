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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.review;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Collectors;

final class GerritCommentLinkFormatter {
  private static final String COMMIT_MESSAGE_FILENAME = "/COMMIT_MSG";
  private static final String COMMIT_MESSAGE_LABEL = "COMMIT MESSAGE";
  private static final String PATCH_SET_LABEL = "PATCH SET";

  private final ChangeSetData changeSetData;
  private final String canonicalWebUrl;

  GerritCommentLinkFormatter(ChangeSetData changeSetData, String canonicalWebUrl) {
    this.changeSetData = changeSetData;
    this.canonicalWebUrl = canonicalWebUrl;
  }

  Optional<String> toCommentLink(
      GerritComment comment, AiReplyItem replyItem, GerritChange change) {
    if (comment.getId() == null
        || comment.getId().isBlank()
        || comment.getFilename() == null
        || comment.getFilename().isBlank()) {
      return Optional.empty();
    }
    String commentUrl = toCommentUrl(comment.getId(), change);
    return Optional.of(
        String.format("[%s](%s)", toCommentLinkLabel(comment, replyItem), commentUrl));
  }

  static String toMarkdownList(Collection<String> items) {
    return items.stream().map(item -> "- " + item).collect(Collectors.joining("\n"));
  }

  private String toCommentLinkLabel(GerritComment comment, AiReplyItem replyItem) {
    if (isCommitMessageReply(replyItem)) {
      return COMMIT_MESSAGE_LABEL;
    }
    String filename = firstNonBlank(replyItem.getFilename(), comment.getFilename());
    Integer line = replyItem.getLineNumber() == null ? comment.getLine() : replyItem.getLineNumber();
    if (COMMIT_MESSAGE_FILENAME.equals(filename)) {
      return COMMIT_MESSAGE_LABEL;
    }
    if (filename != null && !Settings.GERRIT_PATCH_SET_FILENAME.equals(filename)) {
      return line == null ? filename : filename + ":" + line;
    }
    return PATCH_SET_LABEL;
  }

  private boolean isCommitMessageReply(AiReplyItem replyItem) {
    return (replyItem.getFilename() == null && replyItem.getLineNumber() == null)
        || changeSetData.getReviewScope() == ReviewScope.COMMIT_MESSAGE;
  }

  private String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.isBlank()) {
      return primary;
    }
    return fallback == null || fallback.isBlank() ? null : fallback;
  }

  private String toCommentUrl(String commentId, GerritChange change) {
    Optional<Integer> changeNumber = change.getChangeNumber();
    if (changeNumber.isEmpty()) {
      return "#comment-" + commentId;
    }
    String path =
        String.format(
            "/c/%s/+/%d/comment/%s/",
            encodeProjectPath(change.getProjectName()),
            changeNumber.get(),
            encodePathSegment(commentId));
    if (canonicalWebUrl == null || canonicalWebUrl.isBlank()) {
      return path;
    }
    return canonicalWebUrl.endsWith("/")
        ? canonicalWebUrl.substring(0, canonicalWebUrl.length() - 1) + path
        : canonicalWebUrl + path;
  }

  private String encodeProjectPath(String projectName) {
    return Arrays.stream(Optional.ofNullable(projectName).orElse("").split("/", -1))
        .map(this::encodePathSegment)
        .reduce((left, right) -> left + "/" + right)
        .orElse("");
  }

  private String encodePathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
}
