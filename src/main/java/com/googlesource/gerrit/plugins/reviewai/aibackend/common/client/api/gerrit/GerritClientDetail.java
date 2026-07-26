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

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
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

import static com.googlesource.gerrit.plugins.reviewai.utils.StringUtils.backslashBackslashesAndDoubleQuotes;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;

import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TimeZone;

@Slf4j
public class GerritClientDetail {
  private static final SimpleDateFormat DATE_FORMAT = newFormat();

  private final Map<String, GerritPatchSetDetail> gerritPatchSetDetails = new HashMap<>();
  private final int aiAccountId;
  private final Configuration config;

  public GerritClientDetail(Configuration config, ChangeSetData changeSetData) {
    this.aiAccountId = changeSetData.getAiAccountId();
    this.config = config;
    log.debug("Initialized GerritClientDetail for AI account ID: {}", aiAccountId);
  }

  public List<GerritComment> getMessages(GerritChange change) {
    GerritPatchSetDetail gerritPatchSetDetail = loadPatchSetDetail(change);
    log.debug("Retrieving messages for change ID: {}", change.getFullChangeId());
    return Optional.ofNullable(gerritPatchSetDetail.getMessages()).orElse(emptyList());
  }

  public boolean isWorkInProgress(GerritChange change) {
    GerritPatchSetDetail gerritPatchSetDetail = loadPatchSetDetail(change);
    log.debug("Checking if change ID: {} is work in progress", change.getFullChangeId());
    return gerritPatchSetDetail.getWorkInProgress() != null
        && gerritPatchSetDetail.getWorkInProgress();
  }

  public GerritPermittedVotingRange getPermittedVotingRange(GerritChange change) {
    GerritPatchSetDetail gerritPatchSetDetail = loadPatchSetDetail(change);
    if (gerritPatchSetDetail == null || gerritPatchSetDetail.getLabels() == null) {
      return null;
    }
    List<GerritPatchSetDetail.Permission> permissions =
        gerritPatchSetDetail.getLabels().getCodeReviewPermissions();
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
    GerritPatchSetDetail gerritPatchSetDetail = loadPatchSetDetail(change);
    if (gerritPatchSetDetail == null
        || gerritPatchSetDetail.getLabels() == null) {
      return null;
    }
    List<GerritPatchSetDetail.Permission> permissions =
        gerritPatchSetDetail.getLabels().getCodeReviewPermissions();
    if (permissions == null) {
      return null;
    }
    for (GerritPatchSetDetail.Permission permission : permissions) {
      if (ReviewAiUser.matches(permission.getAccountId(), aiAccountId)) {
        return permission.getValue();
      }
    }
    return null;
  }

  /**
   * Fetches the maximum vote per label across all voters for this change.
   *
   * <p>Uses the same REST API path as {@link #getReviewDetail(GerritChange)} but iterates
   * all labels, not just Code-Review. For each label, computes the maximum integer vote
   * value across all voters. If a label has no votes, it is omitted from the result.
   *
   * @param change the Gerrit change
   * @return map of label name to maximum vote value; empty if no labels are present
   */
  public Map<String, Short> fetchCurrentApprovals(GerritChange change) {
    GerritPatchSetDetail detail = loadPatchSetDetail(change);
    if (detail == null || detail.getLabels() == null) {
      return Map.of();
    }

    Map<String, List<GerritPatchSetDetail.Permission>> allLabels =
        detail.getLabels().getAllLabels();
    if (allLabels == null || allLabels.isEmpty()) {
      return Map.of();
    }

    Map<String, Short> result = new HashMap<>();
    for (Entry<String, List<GerritPatchSetDetail.Permission>> entry : allLabels.entrySet()) {
      String labelName = entry.getKey();
      List<GerritPatchSetDetail.Permission> permissions = entry.getValue();
      if (permissions == null || permissions.isEmpty()) {
        continue;
      }
      short maxVote = Short.MIN_VALUE;
      for (GerritPatchSetDetail.Permission permission : permissions) {
        if (permission.getValue() != null) {
          maxVote = (short) Math.max(maxVote, permission.getValue());
        }
      }
      if (maxVote != Short.MIN_VALUE) {
        result.put(labelName, maxVote);
      }
    }

    log.debug("Current approvals for change {}: {}", change.getFullChangeId(), result);
    return result;
  }

  public List<GerritChange> getTopicChanges(GerritChange change) {
    Optional<String> topic = change.getTopic();
    if (topic.isEmpty()) {
      log.info("No topic available for change {}", change.getFullChangeId());
      return emptyList();
    }
    String query =
        String.format(
            "project:%s branch:%s topic:\"%s\" status:open",
            change.getProjectName(),
            change.getBranchNameKey().shortName(),
            backslashBackslashesAndDoubleQuotes(topic.get()));
    try (ManualRequestContext ignored = config.openRequestContext()) {
      return config.getGerritApi().changes().query(query).withNoLimit().get().stream()
          .map(GerritClientDetail::toGerritChange)
          .toList();
    } catch (Exception e) {
      log.error("Error querying topic changes for change {}", change.getFullChangeId(), e);
      return emptyList();
    }
  }

  private GerritPatchSetDetail loadPatchSetDetail(GerritChange change) {
    String changeKey = change.getFullChangeId();
    if (gerritPatchSetDetails.containsKey(changeKey)) {
      return gerritPatchSetDetails.get(changeKey);
    }
    log.debug("Loading patch set detail for change ID: {}", change.getFullChangeId());
    try {
      GerritPatchSetDetail gerritPatchSetDetail = getReviewDetail(change);
      gerritPatchSetDetails.put(changeKey, gerritPatchSetDetail);
      return gerritPatchSetDetail;
    } catch (Exception e) {
      log.error("Error retrieving PatchSet details for change ID: {}", change.getFullChangeId(), e);
      GerritPatchSetDetail emptyDetail = new GerritPatchSetDetail();
      gerritPatchSetDetails.put(changeKey, emptyDetail);
      return emptyDetail;
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
      if (info.labels != null && !info.labels.isEmpty()) {
        GerritPatchSetDetail.Labels labels = new GerritPatchSetDetail.Labels();
        Map<String, List<GerritPatchSetDetail.Permission>> allLabels = new HashMap<>();
        for (Entry<String, LabelInfo> labelEntry : info.labels.entrySet()) {
          String labelName = labelEntry.getKey();
          List<GerritPatchSetDetail.Permission> permissions =
              toPermissions(labelEntry.getValue());
          allLabels.put(labelName, permissions);
        }
        labels.setAllLabels(allLabels);
        detail.setLabels(labels);
      }
      Optional.ofNullable(info.messages)
          .map(messages -> messages.stream().map(GerritClientDetail::toComment).collect(toList()))
          .ifPresent(detail::setMessages);

      return detail;
    }
  }

  static GerritChange toGerritChange(ChangeInfo changeInfo) {
    GerritChange change =
        new GerritChange(
            Project.nameKey(changeInfo.project),
            BranchNameKey.create(Project.nameKey(changeInfo.project), changeInfo.branch),
            Change.key(changeInfo.changeId));
    change.setChangeNumber(changeInfo._number);
    change.setPatchSetNumber(changeInfo.currentRevisionNumber);
    change.setTopic(changeInfo.topic);
    return change;
  }

  private static List<GerritPatchSetDetail.Permission> toPermissions(LabelInfo labelInfo) {
    return Optional.ofNullable(labelInfo.all)
        .map(all -> all.stream().map(GerritClientDetail::toPermission).collect(toList()))
        .orElse(emptyList());
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
