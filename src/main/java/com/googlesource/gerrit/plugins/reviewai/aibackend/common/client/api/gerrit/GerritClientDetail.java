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

import com.google.gerrit.entities.LabelId;
import com.google.gerrit.extensions.common.AccountInfo;
import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.LabelInfo;
import com.google.gerrit.server.util.ManualRequestContext;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPatchSetDetail;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPermittedVotingRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account.ReviewAiUser;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import lombok.extern.slf4j.Slf4j;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;

@Slf4j
public class GerritClientDetail {
  private static final SimpleDateFormat DATE_FORMAT = newFormat();

  private GerritPatchSetDetail gerritPatchSetDetail;
  private final int aiAccountId;
  private final Configuration config;

  public GerritClientDetail(Configuration config, ChangeSetData changeSetData) {
    this.aiAccountId = changeSetData.getAiAccountId();
    this.config = config;
    log.debug("Initialized GerritClientDetail for AI account ID: {}", aiAccountId);
  }

  public List<GerritComment> getMessages(GerritChange change) {
    loadPatchSetDetail(change);
    log.debug("Retrieving messages for change ID: {}", change.getFullChangeId());
    return gerritPatchSetDetail.getMessages();
  }

  public boolean isWorkInProgress(GerritChange change) {
    loadPatchSetDetail(change);
    log.debug("Checking if change ID: {} is work in progress", change.getFullChangeId());
    return gerritPatchSetDetail.getWorkInProgress() != null
        && gerritPatchSetDetail.getWorkInProgress();
  }

  public GerritPermittedVotingRange getPermittedVotingRange(GerritChange change) {
    loadPatchSetDetail(change);
    List<GerritPatchSetDetail.Permission> permissions =
        gerritPatchSetDetail.getLabels().getCodeReview().getAll();
    if (permissions == null) {
      log.debug(
          "No limitations on the AI voting range were detected for change ID: {}",
          change.getFullChangeId());
      return null;
    }
    for (GerritPatchSetDetail.Permission permission : permissions) {
      if (ReviewAiUser.matches(permission.getAccountId(), aiAccountId)) {
        log.debug(
            "PatchSet voting range detected for AI user: {}",
            permission.getPermittedVotingRange());
        return permission.getPermittedVotingRange();
      }
    }
    return null;
  }

  public Integer getCodeReviewValue(GerritChange change) {
    loadPatchSetDetail(change);
    if (gerritPatchSetDetail == null
        || gerritPatchSetDetail.getLabels() == null
        || gerritPatchSetDetail.getLabels().getCodeReview() == null
        || gerritPatchSetDetail.getLabels().getCodeReview().getAll() == null) {
      return null;
    }
    for (GerritPatchSetDetail.Permission permission :
        gerritPatchSetDetail.getLabels().getCodeReview().getAll()) {
      if (ReviewAiUser.matches(permission.getAccountId(), aiAccountId)) {
        return permission.getValue();
      }
    }
    return null;
  }

  private void loadPatchSetDetail(GerritChange change) {
    if (gerritPatchSetDetail != null) {
      return;
    }
    log.debug("Loading patch set detail for change ID: {}", change.getFullChangeId());
    try {
      gerritPatchSetDetail = getReviewDetail(change);
    } catch (Exception e) {
      log.error("Error retrieving PatchSet details for change ID: {}", change.getFullChangeId(), e);
    }
  }

  private GerritPatchSetDetail getReviewDetail(GerritChange change) throws Exception {
    try (ManualRequestContext ignored = config.openRequestContext()) {
      ChangeInfo info =
          config
              .getGerritApi()
              .changes()
              .id(
                  change.getProjectName(),
                  change.getBranchNameKey().shortName(),
                  change.getChangeKey().get())
              .get();
      log.debug("Retrieved change info for change ID: {}", change.getFullChangeId());

      GerritPatchSetDetail detail = new GerritPatchSetDetail();
      detail.setWorkInProgress(info.workInProgress);
      Optional.ofNullable(info.labels)
          .map(Map::entrySet)
          .map(Set::stream)
          .flatMap(
              labels ->
                  labels
                      .filter(label -> LabelId.CODE_REVIEW.equals(label.getKey()))
                      .map(GerritClientDetail::toLabels)
                      .findAny())
          .ifPresent(detail::setLabels);
      Optional.ofNullable(info.messages)
          .map(messages -> messages.stream().map(GerritClientDetail::toComment).collect(toList()))
          .ifPresent(detail::setMessages);

      return detail;
    }
  }

  private static GerritPatchSetDetail.Labels toLabels(Entry<String, LabelInfo> label) {
    List<GerritPatchSetDetail.Permission> permissions =
        Optional.ofNullable(label.getValue().all)
            .map(all -> all.stream().map(GerritClientDetail::toPermission).collect(toList()))
            .orElse(emptyList());
    GerritPatchSetDetail.CodeReview codeReview = new GerritPatchSetDetail.CodeReview();
    codeReview.setAll(permissions);
    GerritPatchSetDetail.Labels labels = new GerritPatchSetDetail.Labels();
    labels.setCodeReview(codeReview);
    return labels;
  }

  private static GerritPatchSetDetail.Permission toPermission(ApprovalInfo value) {
    GerritPatchSetDetail.Permission permission = new GerritPatchSetDetail.Permission();
    permission.setValue(value.value);
    Optional.ofNullable(value.date).ifPresent(date -> permission.setDate(toDateString(date)));
    Optional.ofNullable(value.permittedVotingRange)
        .ifPresent(
            permittedVotingRange -> {
              GerritPermittedVotingRange range = new GerritPermittedVotingRange();
              range.setMin(permittedVotingRange.min);
              range.setMax(permittedVotingRange.max);
              permission.setPermittedVotingRange(range);
            });
    permission.setAccountId(value._accountId);
    return permission;
  }

  private static GerritComment toComment(ChangeMessageInfo message) {
    GerritComment comment = new GerritComment();
    Optional.ofNullable(message.author).ifPresent(author -> comment.setAuthor(toAuthor(author)));
    comment.setId(message.id);
    comment.setTag(message.tag);
    Optional.ofNullable(message.date).ifPresent(date -> comment.setDate(toDateString(date)));
    comment.setMessage(message.message);
    comment.setPatchSet(message._revisionNumber);
    return comment;
  }

  static GerritComment.Author toAuthor(AccountInfo authorInfo) {
    GerritComment.Author author = new GerritComment.Author();
    author.setAccountId(authorInfo._accountId);
    author.setName(authorInfo.name);
    author.setDisplayName(author.getDisplayName());
    author.setEmail(authorInfo.email);
    author.setUsername(authorInfo.username);
    return author;
  }

  /** Date format copied from <b>com.google.gerrit.json.SqlTimestampDeserializer</b> */
  static String toDateString(Timestamp input) {
    return DATE_FORMAT.format(input) + "000000";
  }

  private static SimpleDateFormat newFormat() {
    SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    f.setLenient(true);
    return f;
  }
}
