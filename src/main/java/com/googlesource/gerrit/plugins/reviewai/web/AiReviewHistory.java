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

package com.googlesource.gerrit.plugins.reviewai.web;

import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritAiReviewHistoryCollector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class AiReviewHistory implements RestReadView<ChangeResource> {
  private static final SimpleDateFormat DATE_FORMAT = newFormat();
  private static final Pattern CODE_REVIEW_SCORE_PATTERN =
      Pattern.compile(
          "^"
              + Settings.GERRIT_DEFAULT_MESSAGE_PATCH_SET
              + " \\d+:[^\\n]*\\bCode-Review([+-]\\d+)\\b");

  private final ConfigCreator configCreator;
  private final GerritAiReviewHistoryCollector collector;
  private final AiReviewPermission aiReviewPermission;

  @Inject
  AiReviewHistory(
      ConfigCreator configCreator,
      GerritAiReviewHistoryCollector collector,
      AiReviewPermission aiReviewPermission) {
    this.configCreator = configCreator;
    this.collector = collector;
    this.aiReviewPermission = aiReviewPermission;
  }

  @Override
  public Response<AiReviewHistoryInfo> apply(ChangeResource resource) throws Exception {
    aiReviewPermission.checkCanAiReview(resource);
    Change change = resource.getChange();
    Configuration config = configCreator.createConfig(resource.getProject(), change.getKey());
    Localizer localizer = new Localizer(config);
    String projectName = GerritChange.getProjectName(change.getProject());

    try (ManualRequestContext requestContext = config.openRequestContext()) {
      ChangeApi changeApi = config.getGerritApi().changes().id(projectName, change.getChangeId());
      Map<String, List<CommentInfo>> comments = changeApi.commentsRequest().get();
      ChangeInfo changeInfo = changeApi.get();

      return Response.ok(
          collector.collect(
              config,
              localizer,
              config.getUserId().get(),
              mergeComments(
                  comments,
                  Optional.ofNullable(changeInfo).map(info -> info.messages).orElse(null))));
    }
  }

  static Map<String, List<GerritComment>> mergeComments(
      Map<String, List<CommentInfo>> inlineComments, Collection<ChangeMessageInfo> changeMessages) {
    Map<String, List<GerritComment>> result = toInlineComments(inlineComments);
    List<GerritComment> mergedPatchSetComments =
        new ArrayList<>(result.getOrDefault(Settings.GERRIT_PATCH_SET_FILENAME, List.of()));
    if (changeMessages == null) {
      return result;
    }

    for (ChangeMessageInfo changeMessage : changeMessages) {
      GerritComment patchSetComment = toComment(changeMessage);
      Optional<GerritComment> duplicate =
          mergedPatchSetComments.stream()
              .filter(existing -> isDuplicatePatchSetMessage(existing, patchSetComment))
              .findFirst();
      if (duplicate.isPresent()) {
        if (patchSetComment.getReviewScore() != null) {
          duplicate.get().setReviewScore(patchSetComment.getReviewScore());
        }
      } else {
        mergedPatchSetComments.add(patchSetComment);
      }
    }

    if (!mergedPatchSetComments.isEmpty()) {
      result.put(Settings.GERRIT_PATCH_SET_FILENAME, mergedPatchSetComments);
    }
    return result;
  }

  private static Map<String, List<GerritComment>> toInlineComments(
      Map<String, List<CommentInfo>> comments) {
    Map<String, List<GerritComment>> result = new HashMap<>();
    if (comments == null) {
      return result;
    }
    for (Map.Entry<String, List<CommentInfo>> entry : comments.entrySet()) {
      result.put(
          entry.getKey(),
          entry.getValue().stream()
              .map(comment -> toComment(comment, entry.getKey()))
              .collect(Collectors.toList()));
    }
    return result;
  }

  private static GerritComment toComment(CommentInfo commentInfo, String filename) {
    GerritComment comment = new GerritComment();
    Optional.ofNullable(commentInfo.author).ifPresent(author -> comment.setAuthor(toAuthor(author)));
    comment.setChangeMessageId(commentInfo.changeMessageId);
    comment.setUnresolved(commentInfo.unresolved);
    comment.setPatchSet(commentInfo.patchSet);
    comment.setId(commentInfo.id);
    comment.setTag(commentInfo.tag);
    comment.setLine(commentInfo.line);
    Optional.ofNullable(commentInfo.range)
        .ifPresent(
            range ->
                comment.setRange(
                    GerritCodeRange.builder()
                        .startLine(range.startLine)
                        .startCharacter(range.startCharacter)
                        .endLine(range.endLine)
                        .endCharacter(range.endCharacter)
                        .build()));
    comment.setInReplyTo(commentInfo.inReplyTo);
    Optional.ofNullable(commentInfo.updated).ifPresent(updated -> comment.setUpdated(toDateString(updated)));
    comment.setMessage(commentInfo.message);
    comment.setCommitId(commentInfo.commitId);
    comment.setFilename(filename);
    return comment;
  }

  private static GerritComment toComment(ChangeMessageInfo messageInfo) {
    GerritComment comment = new GerritComment();
    Optional.ofNullable(messageInfo.author).ifPresent(author -> comment.setAuthor(toAuthor(author)));
    comment.setId(messageInfo.id);
    comment.setTag(messageInfo.tag);
    Optional.ofNullable(messageInfo.date).ifPresent(date -> comment.setUpdated(toDateString(date)));
    comment.setMessage(messageInfo.message);
    comment.setPatchSet(messageInfo._revisionNumber);
    comment.setFilename(Settings.GERRIT_PATCH_SET_FILENAME);
    comment.setReviewScore(getCodeReviewScore(messageInfo.message));
    return comment;
  }

  private static boolean isDuplicatePatchSetMessage(GerritComment existing, GerritComment incoming) {
    return Stream.of(existing.getChangeMessageId(), existing.getId())
        .filter(Objects::nonNull)
        .anyMatch(existingId -> existingId.equals(incoming.getId()));
  }

  private static String getCodeReviewScore(String message) {
    return Optional.ofNullable(message)
        .map(CODE_REVIEW_SCORE_PATTERN::matcher)
        .filter(matcher -> matcher.find())
        .map(matcher -> matcher.group(1))
        .orElse(null);
  }

  private static GerritComment.Author toAuthor(AccountInfo authorInfo) {
    GerritComment.Author author = new GerritComment.Author();
    author.setAccountId(authorInfo._accountId);
    author.setName(authorInfo.name);
    author.setDisplayName(authorInfo.displayName);
    author.setEmail(authorInfo.email);
    author.setUsername(authorInfo.username);
    return author;
  }

  private static String toDateString(Timestamp input) {
    return DATE_FORMAT.format(input) + "000000";
  }

  private static SimpleDateFormat newFormat() {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    format.setLenient(true);
    return format;
  }
}
