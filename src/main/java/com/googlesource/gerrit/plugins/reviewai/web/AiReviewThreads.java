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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiHistory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class AiReviewThreads implements RestReadView<ChangeResource> {
  private final ConfigCreator configCreator;
  private final AiReviewPermission aiReviewPermission;

  @Inject
  AiReviewThreads(ConfigCreator configCreator, AiReviewPermission aiReviewPermission) {
    this.configCreator = configCreator;
    this.aiReviewPermission = aiReviewPermission;
  }

  @Override
  public Response<Output> apply(ChangeResource resource) throws Exception {
    aiReviewPermission.checkCanAiReview(resource);
    Change change = resource.getChange();
    Configuration config = configCreator.createConfig(resource.getProject(), change.getKey());
    Localizer localizer = new Localizer(config);
    String projectName = GerritChange.getProjectName(change.getProject());

    try (ManualRequestContext ignored = config.openRequestContext()) {
      var changeApi = config.getGerritApi()
          .changes()
          .id(projectName, change.getChangeId());

      // Fetch comments AND change info, then merge using same logic as the review pipeline.
      // AiReviewHistory.mergeComments() handles inline comments + change messages properly.
      Map<String, List<CommentInfo>> rawComments = changeApi.commentsRequest().get();
      ChangeInfo changeInfo = changeApi.get();
      Collection<ChangeMessageInfo> changeMessages =
          Optional.ofNullable(changeInfo).map(info -> info.messages).orElse(null);
      Map<String, List<GerritComment>> merged =
          AiReviewHistory.mergeComments(rawComments, changeMessages);

      // Flatten merged result into commentMap and patchSetCommentMap,
      // mirroring GerritClientComments.retrieveComments().
      HashMap<String, GerritComment> commentMap = new HashMap<>();
      HashMap<String, GerritComment> patchSetCommentMap = new HashMap<>();

      for (var entry : merged.entrySet()) {
        String filename = entry.getKey();
        for (GerritComment gc : entry.getValue()) {
          commentMap.put(gc.getId(), gc);
          if (Settings.GERRIT_PATCH_SET_FILENAME.equals(filename)) {
            patchSetCommentMap.put(gc.getChangeMessageId(), gc);
          }
        }
      }

      int aiAccountId = config.getUserId().get();
      ChangeSetData changeSetData = new ChangeSetData(aiAccountId);

      // Build AiHistory the same way the review pipeline does
      AiHistory aiHistory = new AiHistory(
          config,
          changeSetData,
          new com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData(
              null,
              List.of(),
              new CommentData(new ArrayList<>(), commentMap, patchSetCommentMap),
              0),
          localizer);

      // Collect threads and concerns
      List<AiHistory.AddressedConcern> addressedConcerns =
          aiHistory.collectPreviouslyAddressedConcerns();

      // Build thread info for every AI comment
      List<ThreadInfo> threads = new ArrayList<>();
      Set<String> processedRootIds = new HashSet<>();

      for (GerritComment comment : commentMap.values()) {
        boolean isAi = ReviewAiUser.matches(comment, aiAccountId);
        if (!isAi || comment.isAutogenerated()) {
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

        ThreadInfo threadInfo = new ThreadInfo();
        threadInfo.rootId = rootId;
        threadInfo.rootIsAi = ReviewAiUser.matches(root, aiAccountId);

        // Walk down from root
        List<ThreadComment> threadComments = new ArrayList<>();
        for (GerritComment threadComment : commentMap.values()) {
          if (isDescendantOf(threadComment, root, commentMap)) {
            ThreadComment tc = new ThreadComment();
            tc.id = threadComment.getId();
            tc.isAi = ReviewAiUser.matches(threadComment, aiAccountId);
            tc.author = threadComment.getAuthor() != null
                ? threadComment.getAuthor().getName() : null;
            tc.inReplyTo = threadComment.getInReplyTo();
            tc.filename = threadComment.getFilename();
            tc.line = threadComment.getLine();
            tc.updated = threadComment.getUpdated();
            tc.tag = threadComment.getTag();
            tc.autogenerated = threadComment.isAutogenerated();
            tc.message = ellipsize(threadComment.getMessage(), 500);
            threadComments.add(tc);
          }
        }
        threadInfo.comments = threadComments;
        threadInfo.size = threadComments.size();
        threadInfo.hasUserReply = threadComments.stream().anyMatch(tc -> !tc.isAi);
        threadInfo.addressed = addressedConcerns.stream()
            .anyMatch(ac -> isSameLocation(ac, threadComments));
        threads.add(threadInfo);
      }

      Output output = new Output();
      output.totalComments = commentMap.size();
      output.aiComments = commentMap.values().stream()
          .filter(c -> ReviewAiUser.matches(c, aiAccountId)).count();
      output.threads = threads;
      output.totalThreads = threads.size();
      output.addressedConcerns = addressedConcerns.stream()
          .map(ac -> new ConcernInfo(ac))
          .collect(Collectors.toList());

      return Response.ok(output);
    }
  }

  private static boolean isDescendantOf(
      GerritComment comment, GerritComment root,
      HashMap<String, GerritComment> commentMap) {
    if (comment.getId().equals(root.getId())) {
      return true;
    }
    GerritComment cursor = comment;
    while (cursor != null && !cursor.getId().equals(root.getId())) {
      String parentId = cursor.getInReplyTo();
      cursor = parentId != null ? commentMap.get(parentId) : null;
    }
    return cursor != null;
  }

  private static boolean isSameLocation(
      AiHistory.AddressedConcern ac, List<ThreadComment> thread) {
    for (ThreadComment tc : thread) {
      if (tc.isAi
          && tc.filename != null && tc.filename.equals(ac.getFilename())
          && tc.line != null && tc.line.equals(ac.getLine())) {
        return true;
      }
    }
    return false;
  }

  private static String ellipsize(String text, int maxLen) {
    if (text == null) return null;
    return text.length() <= maxLen ? text : text.substring(0, maxLen - 3) + "...";
  }

  // ---- output model ----

  public static class Output {
    @SerializedName("total_comments")
    public int totalComments;
    @SerializedName("ai_comments")
    public long aiComments;
    @SerializedName("total_threads")
    public int totalThreads;
    @SerializedName("threads")
    public List<ThreadInfo> threads;
    @SerializedName("addressed_concerns")
    public List<ConcernInfo> addressedConcerns;
  }

  public static class ThreadInfo {
    @SerializedName("root_id")
    public String rootId;
    @SerializedName("root_is_ai")
    public boolean rootIsAi;
    @SerializedName("size")
    public int size;
    @SerializedName("has_user_reply")
    public boolean hasUserReply;
    @SerializedName("addressed")
    public boolean addressed;
    @SerializedName("comments")
    public List<ThreadComment> comments;
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
  }

  public static class ConcernInfo {
    public String filename;
    public Integer line;
    @SerializedName("ai_concern")
    public String aiConcern;
    @SerializedName("user_response")
    public String userResponse;
    @SerializedName("ai_acknowledgment")
    public String aiAcknowledgment;

    ConcernInfo(AiHistory.AddressedConcern ac) {
      this.filename = ac.getFilename();
      this.line = ac.getLine();
      this.aiConcern = ac.getAiConcern();
      this.userResponse = ac.getUserResponse();
      this.aiAcknowledgment = ac.getAiAcknowledgment();
    }
  }
}
