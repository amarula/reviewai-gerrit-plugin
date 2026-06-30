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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account.ReviewAiUser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class RepeatedCommentResolver {
  private static final String SAFE_COMMENT_ID_PATTERN = "[A-Za-z0-9._:-]+";
  private static final int MIN_REPEATED_COMMENT_TEXT_OVERLAP_SCORE = 4;

  private final GerritClient gerritClient;
  private final ChangeSetData changeSetData;

  RepeatedCommentResolver(GerritClient gerritClient, ChangeSetData changeSetData) {
    this.gerritClient = gerritClient;
    this.changeSetData = changeSetData;
  }

  Optional<GerritComment> resolve(AiReplyItem replyItem, GerritChange change) {
    List<GerritComment> comments = getAiComments(change);
    Optional<GerritComment> commentById = getCommentById(comments, replyItem.getRepetitionReplyId());
    if (commentById.isPresent()) {
      return commentById;
    }
    Optional<GerritComment> commentByConcernId =
        getCommentByReferencedConcernId(comments, replyItem.getRepetitionReplyId());
    if (commentByConcernId.isPresent()) {
      return commentByConcernId;
    }
    Optional<GerritComment> commentByLocation =
        comments.stream().filter(comment -> matchesLocation(replyItem, comment)).findFirst();
    if (commentByLocation.isPresent()) {
      return commentByLocation;
    }
    return comments.stream()
        .map(comment -> new CommentMatch(comment, textOverlapScore(replyItem, comment)))
        .filter(match -> match.score() >= MIN_REPEATED_COMMENT_TEXT_OVERLAP_SCORE)
        .max(Comparator.comparingInt(CommentMatch::score))
        .map(CommentMatch::comment);
  }

  private Optional<GerritComment> getCommentById(List<GerritComment> comments, String commentId) {
    if (commentId == null || !commentId.trim().matches(SAFE_COMMENT_ID_PATTERN)) {
      return Optional.empty();
    }
    String id = commentId.trim();
    return comments.stream().filter(comment -> id.equals(comment.getId())).findFirst();
  }

  private Optional<GerritComment> getCommentByReferencedConcernId(
      List<GerritComment> comments, String commentId) {
    if (commentId == null || !commentId.trim().matches(SAFE_COMMENT_ID_PATTERN)) {
      return Optional.empty();
    }
    String id = commentId.trim();
    Pattern idPattern =
        Pattern.compile(
            "(^|[^" + idBoundaryCharacters(id) + "])"
                + Pattern.quote(id)
                + "([^"
                + idBoundaryCharacters(id)
                + "]|$)");
    return comments.stream()
        .filter(
            comment -> comment.getMessage() != null && idPattern.matcher(comment.getMessage()).find())
        .findFirst();
  }

  private String idBoundaryCharacters(String id) {
    return id.contains(":") ? "A-Za-z0-9._:-" : "A-Za-z0-9._-";
  }

  private boolean matchesLocation(AiReplyItem replyItem, GerritComment comment) {
    boolean filenameMatches =
        replyItem.getFilename() != null
            && comment.getFilename() != null
            && replyItem.getFilename().equals(comment.getFilename());
    boolean lineMatches =
        replyItem.getLineNumber() != null
            && comment.getLine() != null
            && replyItem.getLineNumber().equals(comment.getLine());
    return filenameMatches && lineMatches;
  }

  private int textOverlapScore(AiReplyItem replyItem, GerritComment comment) {
    Set<String> replyTokens = tokenize(replyItem.getReply() + " " + replyItem.getRepeatedReason());
    Set<String> commentTokens = tokenize(comment.getMessage());
    replyTokens.retainAll(commentTokens);
    return replyTokens.size();
  }

  private Set<String> tokenize(String text) {
    if (text == null || text.isBlank()) {
      return Set.of();
    }
    Set<String> tokens = new HashSet<>();
    for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) {
      if (token.length() >= 4) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  private List<GerritComment> getAiComments(GerritChange change) {
    GerritClientData clientData = gerritClient.getClientData(change);
    if (clientData == null) {
      return List.of();
    }
    List<GerritComment> comments = new ArrayList<>();
    addAiComments(comments, clientData.getDetailComments());
    CommentData commentData = clientData.getCommentData();
    if (commentData != null) {
      addAiComments(comments, commentData.getCommentProperties());
      if (commentData.getCommentMap() != null) {
        addAiComments(comments, commentData.getCommentMap().values());
      }
      if (commentData.getPatchSetCommentMap() != null) {
        addAiComments(comments, commentData.getPatchSetCommentMap().values());
      }
    }
    Map<String, GerritComment> uniqueComments = new LinkedHashMap<>();
    for (GerritComment comment : comments) {
      uniqueComments.putIfAbsent(comment.getId(), comment);
    }
    return new ArrayList<>(uniqueComments.values());
  }

  private void addAiComments(List<GerritComment> target, Collection<GerritComment> comments) {
    if (comments == null) {
      return;
    }
    for (GerritComment comment : comments) {
      if (comment != null
          && comment.getId() != null
          && ReviewAiUser.matches(comment, changeSetData.getAiAccountId())) {
        target.add(comment);
      }
    }
  }

  private record CommentMatch(GerritComment comment, int score) {}
}
