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

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account.ReviewAiUser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.ClientMessageParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static java.util.stream.Collectors.toList;

import java.util.*;

import static com.googlesource.gerrit.plugins.reviewai.utils.TimeUtils.getEpochSeconds;
import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientDetail.toAuthor;
import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientDetail.toDateString;
import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_PATCH_SET_FILENAME;

@Slf4j
public class GerritClientComments extends GerritClientAccount {
  private static final Integer MAX_SECS_GAP_BETWEEN_EVENT_AND_COMMENT = 2;

  private final ChangeSetData changeSetData;
  private final ICodeContextPolicy codeContextPolicy;
  private final IGerritClientPatchSet gerritClientPatchSet;
  private final HashMap<String, GerritComment> commentMap;
  private final HashMap<String, GerritComment> patchSetCommentMap;
  private final PluginDataHandlerProvider pluginDataHandlerProvider;
  private final Localizer localizer;
  private final PluginChatMemoryStore chatMemoryStore;

  private String authorUsername;
  @Getter private List<GerritComment> commentProperties;

  @VisibleForTesting
  public GerritClientComments(
      Configuration config,
      ChangeSetData changeSetData,
      ICodeContextPolicy codeContextPolicy,
      IGerritClientPatchSet gerritClientPatchSet,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer) {
    this(
        config,
        changeSetData,
        codeContextPolicy,
        gerritClientPatchSet,
        pluginDataHandlerProvider,
        localizer,
        null);
  }

  @Inject
  public GerritClientComments(
      Configuration config,
      ChangeSetData changeSetData,
      ICodeContextPolicy codeContextPolicy,
      IGerritClientPatchSet gerritClientPatchSet,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      PluginChatMemoryStore chatMemoryStore) {
    super(config);
    this.changeSetData = changeSetData;
    this.codeContextPolicy = codeContextPolicy;
    this.gerritClientPatchSet = gerritClientPatchSet;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.localizer = localizer;
    this.chatMemoryStore = chatMemoryStore;
    commentProperties = new ArrayList<>();
    commentMap = new HashMap<>();
    patchSetCommentMap = new HashMap<>();
  }

  public CommentData getCommentData() {
    return new CommentData(commentProperties, commentMap, patchSetCommentMap);
  }

  public boolean retrieveLastComments(GerritChange change) {
    CommentAddedEvent commentAddedEvent = (CommentAddedEvent) change.getEvent();
    AccountAttribute author = commentAddedEvent.author.get();
    authorUsername = author.username;
    log.debug("Found comments by '{}' on {}", authorUsername, change.getEventTimeStamp());
    if (ReviewAiUser.matches(
        author, config.getUserId(), config.getGerritUserName(), config.getGerritUserEmail())) {
      log.debug("These are the Bot's own comments, do not process them.");
      return false;
    }
    if (isDisabledUser(authorUsername)) {
      log.info("Review of comments from user '{}' is disabled.", authorUsername);
      return false;
    }
    addLastComments(change);

    return !commentProperties.isEmpty();
  }

  public void retrieveAllComments(GerritChange change) {
    try {
      retrieveComments(change);
    } catch (Exception e) {
      log.error("Error while retrieving all comments for change: {}", change.getFullChangeId(), e);
    }
  }

  private List<GerritComment> retrieveComments(GerritChange change) throws Exception {
    try (ManualRequestContext ignored = config.openRequestContext()) {
      Map<String, List<CommentInfo>> comments =
          config
              .getGerritApi()
              .changes()
              .id(
                  change.getProjectName(),
                  change.getBranchNameKey().shortName(),
                  change.getChangeKey().get())
              .commentsRequest()
              .get();

      // note that list of Map.Entry was used in order to keep the original response order
      List<Map.Entry<String, List<GerritComment>>> lastCommentEntries =
          comments.entrySet().stream()
              .map(
                  entry ->
                      Map.entry(
                          entry.getKey(),
                          entry.getValue().stream()
                              .map(GerritClientComments::toComment)
                              .collect(toList())))
              .collect(toList());

      String latestChangeMessageId = null;
      HashMap<String, List<GerritComment>> latestComments = new HashMap<>();
      for (Map.Entry<String, List<GerritComment>> entry : lastCommentEntries) {
        String filename = entry.getKey();
        log.info("Commented filename: {}", filename);

        List<GerritComment> commentsArray = entry.getValue();

        for (GerritComment commentObject : commentsArray) {
          commentObject.setFilename(filename);
          String commentId = commentObject.getId();
          String changeMessageId = commentObject.getChangeMessageId();
          String commentAuthorUsername = commentObject.getAuthor().getUsername();
          log.debug("Change Message Object: {}", commentObject);
          long updatedTimeStamp = getEpochSeconds(commentObject.getUpdated());
          if (commentAuthorUsername.equals(authorUsername)
              && updatedTimeStamp
                  >= change.getEventTimeStamp() - MAX_SECS_GAP_BETWEEN_EVENT_AND_COMMENT) {
            log.debug("Found comment with updatedTimeStamp : {}", updatedTimeStamp);
            latestChangeMessageId = changeMessageId;
          }
          latestComments
              .computeIfAbsent(changeMessageId, k -> new ArrayList<>())
              .add(commentObject);
          commentMap.put(commentId, commentObject);
          if (filename.equals(GERRIT_PATCH_SET_FILENAME)) {
            patchSetCommentMap.put(changeMessageId, commentObject);
          }
        }
      }

      return latestComments.getOrDefault(latestChangeMessageId, null);
    }
  }

  private void addLastComments(GerritChange change) {
    log.debug("Adding last comments for change: {}", change.getFullChangeId());
    ClientMessageParser messageParser =
        new ClientMessageParser(
            config,
            changeSetData,
            change,
            codeContextPolicy,
            pluginDataHandlerProvider,
            localizer,
            () -> gerritClientPatchSet.getPatchSet(changeSetData, change),
            chatMemoryStore);
    try {
      List<GerritComment> latestComments = retrieveComments(change);
      if (latestComments == null) {
        return;
      }
      for (GerritComment latestComment : latestComments) {
        String commentMessage = latestComment.getMessage();
        log.debug("Processing comment: {}", commentMessage);
        if (messageParser.isBotAddressed(commentMessage)) {
          if (messageParser.parseCommands(commentMessage)) {
            commentProperties.clear();
            return;
          }
          commentProperties.add(latestComment);
        }
      }
    } catch (Exception e) {
      log.error("Error while retrieving last comments for change: {}", change.getFullChangeId(), e);
    }
  }

  private static GerritComment toComment(CommentInfo comment) {
    GerritComment gerritComment = new GerritComment();
    gerritComment.setAuthor(toAuthor(comment.author));
    gerritComment.setChangeMessageId(comment.changeMessageId);
    gerritComment.setUnresolved(comment.unresolved);
    gerritComment.setPatchSet(comment.patchSet);
    gerritComment.setId(comment.id);
    gerritComment.setTag(comment.tag);
    gerritComment.setLine(comment.line);
    Optional.ofNullable(comment.range)
        .ifPresent(
            range ->
                gerritComment.setRange(
                    GerritCodeRange.builder()
                        .startLine(range.startLine)
                        .endLine(range.endLine)
                        .startCharacter(range.startCharacter)
                        .endCharacter(range.endCharacter)
                        .build()));
    gerritComment.setInReplyTo(comment.inReplyTo);
    Optional.ofNullable(comment.updated)
        .ifPresent(updated -> gerritComment.setUpdated(toDateString(updated)));
    gerritComment.setMessage(comment.message);
    gerritComment.setCommitId(comment.commitId);
    return gerritComment;
  }
}
