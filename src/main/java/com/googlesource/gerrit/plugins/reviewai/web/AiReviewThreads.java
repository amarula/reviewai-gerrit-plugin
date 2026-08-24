/*
 * Copyright (c) 2026. Amarula Solutions
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
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.extensions.restapi.RestReadView;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gson.annotations.SerializedName;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account.ReviewAiUser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritCommentThreadIndex;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackPublisher;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Returns the Gerrit comment threads containing ReviewAI comments for a change. */
public class AiReviewThreads implements RestReadView<ChangeResource> {
  private static final int MAX_MESSAGE_LENGTH = 500;
  private static final Comparator<GerritComment> COMMENT_ORDER =
      Comparator.comparing(
              AiReviewThreads::timestamp,
              Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(GerritComment::getId, Comparator.nullsLast(Comparator.naturalOrder()));

  private final ConfigCreator configCreator;
  private final AiReviewPermission aiReviewPermission;
  private final ReviewConcernPublisher reviewConcernPublisher;
  private final ReviewFeedbackPublisher reviewFeedbackPublisher;

  @Inject
  AiReviewThreads(
      ConfigCreator configCreator,
      AiReviewPermission aiReviewPermission,
      ReviewConcernPublisher reviewConcernPublisher,
      ReviewFeedbackPublisher reviewFeedbackPublisher) {
    this.configCreator = configCreator;
    this.aiReviewPermission = aiReviewPermission;
    this.reviewConcernPublisher = reviewConcernPublisher;
    this.reviewFeedbackPublisher = reviewFeedbackPublisher;
  }

  @Override
  public Response<Output> apply(ChangeResource resource) throws Exception {
    aiReviewPermission.checkCanAiReview(resource);
    Change change = resource.getChange();
    Configuration config = configCreator.createConfig(resource.getProject(), change.getKey());
    String projectName = GerritChange.getProjectName(change.getProject());

    try (ManualRequestContext ignored = config.openRequestContext()) {
      ChangeApi changeApi = config.getGerritApi().changes().id(projectName, change.getChangeId());
      Map<String, List<CommentInfo>> inlineComments = changeApi.commentsRequest().get();
      ChangeInfo changeInfo = changeApi.get();
      Map<String, List<GerritComment>> mergedComments =
          AiReviewHistory.mergeComments(
              inlineComments, Optional.ofNullable(changeInfo).map(info -> info.messages).orElse(null));
      Output output = buildOutput(flatten(mergedComments), config.getUserId().get());
      GerritChange gerritChange =
          new GerritChange(resource.getProject(), change.getDest(), change.getKey());
      reviewConcernPublisher.load(gerritChange).ifPresent(ledger -> annotateWithLedger(output, ledger));
      annotateWithFeedback(
          output,
          reviewFeedbackPublisher.load(gerritChange).orElse(null),
          reviewFeedbackPublisher.listComments(gerritChange));
      return Response.ok(output);
    }
  }

  static Output buildOutput(Collection<GerritComment> comments, int aiAccountId) {
    List<GerritComment> allComments = new ArrayList<>(comments);
    GerritCommentThreadIndex threadIndex = new GerritCommentThreadIndex(allComments);
    Map<String, GerritComment> roots = new HashMap<>();
    for (GerritComment comment : allComments) {
      if (!ReviewAiUser.matches(comment, aiAccountId) || comment.isAutogenerated()) {
        continue;
      }
      threadIndex.rootOf(comment).ifPresent(root -> roots.putIfAbsent(root.getId(), root));
    }

    List<ThreadInfo> threads =
        roots.values().stream()
            .sorted(COMMENT_ORDER)
            .map(root -> toThread(root, threadIndex, aiAccountId))
            .toList();

    Output output = new Output();
    output.totalComments = allComments.size();
    output.aiComments =
        allComments.stream().filter(comment -> ReviewAiUser.matches(comment, aiAccountId)).count();
    output.totalThreads = threads.size();
    output.threads = threads;
    return output;
  }

  static void annotateWithLedger(Output output, ReviewConcernLedger ledger) {
    ledger.normalize();
    output.concernLedger = new LedgerInfo(ledger);
    Map<String, List<String>> concernIdsByCommentId = new HashMap<>();
    for (ReviewerConcerns reviewer : ledger.getReviewers()) {
      for (ReviewConcern concern : reviewer.getConcerns()) {
        String commentId = concern.getPreviousCommentId();
        if (commentId != null && !commentId.isBlank()) {
          concernIdsByCommentId.computeIfAbsent(commentId, ignored -> new ArrayList<>()).add(concern.getId());
        }
      }
    }
    for (ThreadInfo thread : output.threads) {
      thread.concernIds =
          thread.comments.stream()
              .map(comment -> concernIdsByCommentId.getOrDefault(comment.id, List.of()))
              .flatMap(Collection::stream)
              .distinct()
              .toList();
    }
  }

  static void annotateWithFeedback(
      Output output,
      ReviewFeedbackMemory feedbackMemory,
      List<ReviewFeedbackStore.FeedbackComment> feedbackComments) {
    if (feedbackMemory != null) {
      output.feedbackMemory = new FeedbackMemoryInfo(feedbackMemory);
    }
    output.feedbackComments = feedbackComments.stream().map(FeedbackCommentInfo::new).toList();
    Map<String, String> feedbackStatesByCommentId = new HashMap<>();
    for (ReviewFeedbackStore.FeedbackComment comment : feedbackComments) {
      feedbackStatesByCommentId.put(comment.commentId(), comment.processingState());
    }
    Map<String, String> concernIdsByCommentId = concernIdsByCommentId(output.concernLedger);
    for (ThreadInfo thread : output.threads) {
      Map<String, ThreadComment> commentsById = new HashMap<>();
      thread.comments.forEach(comment -> commentsById.put(comment.id, comment));
      for (ThreadComment comment : thread.comments) {
        comment.feedbackState = feedbackStatesByCommentId.get(comment.id);
        if (comment.feedbackState != null) {
          comment.threadConcernId = threadConcernId(comment, commentsById, concernIdsByCommentId);
        }
      }
    }
  }

  private static Map<String, String> concernIdsByCommentId(LedgerInfo ledger) {
    if (ledger == null) {
      return Map.of();
    }
    Map<String, String> concernIds = new HashMap<>();
    for (ReviewerInfo reviewer : ledger.reviewers) {
      for (ConcernInfo concern : reviewer.concerns) {
        if (concern.previousCommentId != null && !concern.previousCommentId.isBlank()) {
          concernIds.putIfAbsent(concern.previousCommentId, concern.id);
        }
      }
    }
    return concernIds;
  }

  private static String threadConcernId(
      ThreadComment comment,
      Map<String, ThreadComment> commentsById,
      Map<String, String> concernIdsByCommentId) {
    Set<String> visitedIds = new HashSet<>();
    ThreadComment current = comment;
    while (current != null && visitedIds.add(current.id)) {
      String concernId = concernIdsByCommentId.get(current.id);
      if (concernId != null) {
        return concernId;
      }
      current = commentsById.get(current.inReplyTo);
    }
    return null;
  }

  private static Collection<GerritComment> flatten(
      Map<String, List<GerritComment>> commentsByFilename) {
    List<GerritComment> comments = new ArrayList<>();
    commentsByFilename.values().forEach(comments::addAll);
    return comments;
  }

  private static ThreadInfo toThread(
      GerritComment root, GerritCommentThreadIndex threadIndex, int aiAccountId) {
    List<ThreadComment> comments = new ArrayList<>();
    addThreadComments(root, threadIndex, aiAccountId, comments, new HashSet<>());

    ThreadInfo thread = new ThreadInfo();
    thread.rootId = root.getId();
    thread.rootIsAi = ReviewAiUser.matches(root, aiAccountId);
    thread.size = comments.size();
    thread.hasUserReply = comments.stream().anyMatch(comment -> !comment.isAi);
    thread.comments = comments;
    return thread;
  }

  private static void addThreadComments(
      GerritComment comment,
      GerritCommentThreadIndex threadIndex,
      int aiAccountId,
      List<ThreadComment> result,
      Set<String> visitedIds) {
    if (comment.getId() == null || !visitedIds.add(comment.getId())) {
      return;
    }
    result.add(toThreadComment(comment, aiAccountId));
    threadIndex
        .childrenOf(comment)
        .forEach(child -> addThreadComments(child, threadIndex, aiAccountId, result, visitedIds));
  }

  private static ThreadComment toThreadComment(GerritComment comment, int aiAccountId) {
    ThreadComment result = new ThreadComment();
    result.id = comment.getId();
    result.isAi = ReviewAiUser.matches(comment, aiAccountId);
    result.author = comment.getAuthor() == null ? null : comment.getAuthor().getName();
    result.inReplyTo = comment.getInReplyTo();
    result.filename = comment.getFilename();
    result.line = comment.getLine();
    result.updated = comment.getUpdated();
    result.tag = comment.getTag();
    result.autogenerated = comment.isAutogenerated();
    result.message = ellipsize(comment.getMessage(), MAX_MESSAGE_LENGTH);
    return result;
  }

  private static String timestamp(GerritComment comment) {
    return comment.getUpdated() != null ? comment.getUpdated() : comment.getDate();
  }

  private static String ellipsize(String text, int maxLength) {
    if (text == null || text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength - 3) + "...";
  }

  public static class Output {
    @SerializedName("total_comments")
    public int totalComments;

    @SerializedName("ai_comments")
    public long aiComments;

    @SerializedName("total_threads")
    public int totalThreads;

    public List<ThreadInfo> threads;

    @SerializedName("concern_ledger")
    public LedgerInfo concernLedger;

    @SerializedName("feedback_memory")
    public FeedbackMemoryInfo feedbackMemory;

    @SerializedName("feedback_comments")
    public List<FeedbackCommentInfo> feedbackComments = List.of();
  }

  public static class ThreadInfo {
    @SerializedName("root_id")
    public String rootId;

    @SerializedName("root_is_ai")
    public boolean rootIsAi;

    public int size;

    @SerializedName("has_user_reply")
    public boolean hasUserReply;

    public List<ThreadComment> comments;

    @SerializedName("concern_ids")
    public List<String> concernIds = List.of();
  }

  public static class ThreadComment {
    public String id;

    @SerializedName("is_ai")
    public boolean isAi;

    public String author;

    @SerializedName("in_reply_to")
    public String inReplyTo;

    public String filename;
    public Integer line;
    public String updated;
    public String tag;
    public boolean autogenerated;
    public String message;

    @SerializedName("feedback_state")
    public String feedbackState;

    @SerializedName("thread_concern_id")
    public String threadConcernId;
  }

  public static class LedgerInfo {
    @SerializedName("schema_version")
    public int schemaVersion;

    @SerializedName("last_reviewed_commit")
    public String lastReviewedCommit;

    public List<ReviewerInfo> reviewers;

    LedgerInfo(ReviewConcernLedger ledger) {
      schemaVersion = ledger.getSchemaVersion();
      lastReviewedCommit = ledger.getLastReviewedCommit();
      reviewers = ledger.getReviewers().stream().map(ReviewerInfo::new).toList();
    }
  }

  public static class ReviewerInfo {
    public String kind;
    public String name;
    public List<ConcernInfo> concerns;

    ReviewerInfo(ReviewerConcerns reviewer) {
      kind = reviewer.getReviewer().getKind().name();
      name = reviewer.getReviewer().getName();
      concerns = reviewer.getConcerns().stream().map(ConcernInfo::new).toList();
    }
  }

  public static class ConcernInfo {
    public String id;
    public String status;

    @SerializedName("status_reason")
    public String statusReason;

    public String type;

    @SerializedName("owner_agent")
    public String ownerAgent;

    public String description;

    @SerializedName("previous_comment_id")
    public String previousCommentId;

    public List<LocationInfo> locations;

    ConcernInfo(ReviewConcern concern) {
      id = concern.getId();
      status = concern.getStatus().name();
      statusReason = concern.getStatusReason();
      type = concern.getType();
      ownerAgent = concern.getOwnerAgent();
      description = concern.getDescription();
      previousCommentId = concern.getPreviousCommentId();
      locations = concern.getLocations().stream().map(LocationInfo::new).toList();
    }
  }

  public static class LocationInfo {
    public String filename;

    @SerializedName("line_number")
    public Integer lineNumber;

    @SerializedName("code_snippet")
    public String codeSnippet;

    LocationInfo(ConcernLocation location) {
      filename = location.getFilename();
      lineNumber = location.getLineNumber();
      codeSnippet = location.getCodeSnippet();
    }
  }

  public static class FeedbackMemoryInfo {
    @SerializedName("schema_version")
    public int schemaVersion;

    @SerializedName("generic_feedback")
    public String genericFeedback;

    @SerializedName("concern_feedback")
    public Map<String, String> concernFeedback;

    @SerializedName("disabled_review_scopes")
    public Set<ReviewScope> disabledReviewScopes;

    @SerializedName("disabled_specialized_agents")
    public Set<String> disabledSpecializedAgents;

    FeedbackMemoryInfo(ReviewFeedbackMemory memory) {
      schemaVersion = memory.getSchemaVersion();
      genericFeedback = memory.getGenericFeedback();
      concernFeedback = memory.getConcernFeedback();
      disabledReviewScopes = memory.getDisabledReviewScopes();
      disabledSpecializedAgents = memory.getDisabledSpecializedAgents();
    }
  }

  public static class FeedbackCommentInfo {
    @SerializedName("comment_id")
    public String commentId;

    @SerializedName("processing_state")
    public String processingState;

    FeedbackCommentInfo(ReviewFeedbackStore.FeedbackComment comment) {
      commentId = comment.commentId();
      processingState = comment.processingState();
    }
  }
}
