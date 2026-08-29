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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.reflect.TypeToken;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.api.changes.ChangeApi.CommentsRequest;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.json.OutputFormat;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.GerritReviewException;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GerritClientReviewTest {
  @Mock private Configuration config;
  @Mock private PluginDataHandlerProvider pluginDataHandlerProvider;
  @Mock private Localizer localizer;
  @Mock private GerritApi gerritApi;
  @Mock private Changes changes;
  @Mock private ChangeApi changeApi;
  @Mock private ChangeApi refreshedChangeApi;
  @Mock private RevisionApi revisionApi;
  @Mock private ReviewResult reviewResult;
  @Mock private CommentsRequest commentsRequest;
  @Mock private CommentsRequest refreshedCommentsRequest;

  private GerritClientReview client;
  private GerritChange change;
  private ChangeSetData changeSetData;

  @Before
  public void setUp() throws Exception {
    change =
        new GerritChange(
            "project", BranchNameKey.create("project", "main"), Change.key("I1234567890"));
    changeSetData = new ChangeSetData(1);
    when(config.getGerritApi()).thenReturn(gerritApi);
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id("project", "main", "I1234567890"))
        .thenReturn(changeApi, refreshedChangeApi);
    when(changeApi.current()).thenReturn(revisionApi);
    when(revisionApi.review(any(ReviewInput.class))).thenReturn(reviewResult);
    lenient()
        .when(localizer.getText("message.review.concern.resolution"))
        .thenReturn("Resolved by ReviewAI (%s): %s");
    lenient()
        .when(localizer.getText("message.review.concern.resolution.fixed"))
        .thenReturn("the concern is fixed in the current patch set.");
    lenient()
        .when(localizer.getText("message.review.concern.resolution.dismissed"))
        .thenReturn("the concern was dismissed as non-actionable.");
    lenient()
        .when(localizer.getText("message.review.concern.resolution.skipped"))
        .thenReturn("the concern's review scope is disabled.");
    lenient()
        .when(localizer.getText("message.empty.review"))
        .thenReturn("No update to show for this Change Set");
    client = new GerritClientReview(config, pluginDataHandlerProvider, localizer);
  }

  @Test
  public void successfulReviewReturnsNormally() throws Exception {
    client.setReview(change, List.of(new ReviewBatch("Review comment")), changeSetData);

    verify(revisionApi).review(any(ReviewInput.class));
  }

  @Test
  public void blankIncrementalPatchSetSuppressesEmptyReviewMessage() throws Exception {
    changeSetData.setIncrementalPatchSet("");

    client.setReview(change, List.of(), changeSetData);

    verify(revisionApi, never()).review(any(ReviewInput.class));
  }

  @Test
  public void reviewWithoutIncrementalPatchPostsEmptyReviewMessage() throws Exception {
    client.setReview(change, List.of(), changeSetData);

    ArgumentCaptor<ReviewInput> reviewInputCaptor = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApi).review(reviewInputCaptor.capture());
    assertEquals("No update to show for this Change Set", reviewInputCaptor.getValue().message);
  }

  @Test
  public void reviewResultErrorIsSurfaced() throws Exception {
    reviewResult.error = "submission rejected";

    try {
      client.setReview(change, List.of(new ReviewBatch("Review comment")), changeSetData);
      fail("Expected Gerrit review failure");
    } catch (GerritReviewException e) {
      assertEquals("submission rejected", e.getMessage());
    }
  }

  @Test
  public void returnsPublishedCommentIdsWhenGerritOmitsReviewTag() throws Exception {
    Map<String, List<CommentInfo>> comments = readPublishedComments();
    Map<String, List<CommentInfo>> existingComments = existingComments(comments);
    List<ReviewBatch> batches = new ArrayList<>();
    Map<String, String> expectedCommentIds = new LinkedHashMap<>();
    comments.forEach(
        (filename, filenameComments) ->
            filenameComments.stream()
                .filter(comment -> comment.tag == null)
                .forEach(
                    comment -> {
                      ReviewBatch batch = new ReviewBatch(comment.message);
                      batch.setConcernId("concern-" + comment.id);
                      batch.setId(comment.inReplyTo);
                      batch.setLine(comment.line);
                      if (!batch.getFilename().equals(filename)) {
                        batch.setFilename(filename);
                      }
                      batches.add(batch);
                      expectedCommentIds.put(batch.getConcernId(), comment.id);
                    }));
    when(changeApi.commentsRequest()).thenReturn(commentsRequest);
    when(commentsRequest.get()).thenReturn(existingComments);
    when(refreshedChangeApi.commentsRequest()).thenReturn(refreshedCommentsRequest);
    when(refreshedCommentsRequest.get()).thenReturn(comments);
    when(revisionApi.review(any(ReviewInput.class)))
        .thenAnswer(
            invocation -> {
              ReviewInput input = invocation.getArgument(0);
              assertNotNull(input.tag);
              return reviewResult;
            });

    assertEquals(
        expectedCommentIds,
        client.setReviewAndGetPublishedCommentIds(change, batches, changeSetData, null));
  }

  @Test
  public void publishesMainReviewAndFixedConcernResolutionTogether() throws Exception {
    CommentInfo comment = new CommentInfo();
    comment.id = "ai-concern";
    comment.tag = "reviewai:concerns:review-1";
    comment.line = 42;
    comment.unresolved = true;
    when(changeApi.commentsRequest()).thenReturn(commentsRequest);
    when(commentsRequest.get()).thenReturn(Map.of("src/Example.java", List.of(comment)));
    changeSetData.setReviewRepeatedCommentsMessage("My previous comment still holds");

    client.setReviewAndGetPublishedCommentIds(
        change,
        List.of(new ReviewBatch("Review comment")),
        changeSetData,
        null,
        concernResponse(
            concern("ai-concern", ConcernStatus.FIXED, "The guard now handles the input.")));

    ArgumentCaptor<ReviewInput> reviewInputCaptor = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApi).review(reviewInputCaptor.capture());
    ReviewInput reviewInput = reviewInputCaptor.getValue();
    assertEquals("My previous comment still holds", reviewInput.message);
    assertEquals("Review comment", reviewInput.comments.get("/PATCHSET_LEVEL").getFirst().message);
    ReviewInput.CommentInput resolution = reviewInput.comments.get("src/Example.java").getFirst();
    assertEquals("ai-concern", resolution.inReplyTo);
    assertEquals(Integer.valueOf(42), resolution.line);
    assertFalse(resolution.unresolved);
    assertEquals(
        "Resolved by ReviewAI (FIXED): The guard now handles the input.", resolution.message);
  }

  @Test
  public void resolvesDismissedAndSkippedConcernThreads() throws Exception {
    CommentInfo dismissedComment = openTaggedComment("dismissed-comment");
    CommentInfo skippedComment = openTaggedComment("skipped-comment");
    when(changeApi.commentsRequest()).thenReturn(commentsRequest);
    when(commentsRequest.get())
        .thenReturn(
            Map.of("src/Example.java", List.of(dismissedComment, skippedComment)));
    changeSetData.setReviewSystemMessage("Main review message");

    client.setReviewAndGetPublishedCommentIds(
        change,
        List.of(),
        changeSetData,
        null,
        concernResponse(
            concern("dismissed-comment", ConcernStatus.DISMISSED, null),
            concern("skipped-comment", ConcernStatus.SKIPPED, null)));

    ArgumentCaptor<ReviewInput> reviewInputCaptor = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApi).review(reviewInputCaptor.capture());
    assertEquals("Main review message", reviewInputCaptor.getValue().message);
    List<ReviewInput.CommentInput> resolutions =
        reviewInputCaptor.getValue().comments.get("src/Example.java");
    assertEquals(2, resolutions.size());
    assertEquals("dismissed-comment", resolutions.get(0).inReplyTo);
    assertEquals(
        "Resolved by ReviewAI (DISMISSED): the concern was dismissed as non-actionable.",
        resolutions.get(0).message);
    assertEquals("skipped-comment", resolutions.get(1).inReplyTo);
    assertEquals(
        "Resolved by ReviewAI (SKIPPED): the concern's review scope is disabled.",
        resolutions.get(1).message);
  }

  @Test
  public void doesNotResolveAnAlreadyResolvedConcernThread() throws Exception {
    CommentInfo root = openTaggedComment("ai-concern");
    root.setUpdated(Instant.parse("2026-08-27T10:00:00Z"));
    CommentInfo resolution = reply("resolution", root.id, false, "2026-08-27T10:01:00Z");
    when(changeApi.commentsRequest()).thenReturn(commentsRequest);
    when(commentsRequest.get())
        .thenReturn(Map.of("src/Example.java", List.of(root, resolution)));
    changeSetData.setReviewSystemMessage("Main review message");

    client.setReviewAndGetPublishedCommentIds(
        change,
        List.of(),
        changeSetData,
        null,
        concernResponse(
            concern("ai-concern", ConcernStatus.FIXED, "The guard now handles the input.")));

    ArgumentCaptor<ReviewInput> reviewInputCaptor = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApi).review(reviewInputCaptor.capture());
    assertNull(reviewInputCaptor.getValue().comments);
  }

  @Test
  public void resolvesAConcernThreadReopenedAfterResolution() throws Exception {
    CommentInfo root = openTaggedComment("ai-concern");
    root.setUpdated(Instant.parse("2026-08-27T10:00:00Z"));
    CommentInfo resolution = reply("resolution", root.id, false, "2026-08-27T10:01:00Z");
    CommentInfo reopeningReply =
        reply("reopening-reply", resolution.id, true, "2026-08-27T10:02:00Z");
    when(changeApi.commentsRequest()).thenReturn(commentsRequest);
    when(commentsRequest.get())
        .thenReturn(Map.of("src/Example.java", List.of(root, resolution, reopeningReply)));
    changeSetData.setReviewSystemMessage("Main review message");

    client.setReviewAndGetPublishedCommentIds(
        change,
        List.of(),
        changeSetData,
        null,
        concernResponse(
            concern("ai-concern", ConcernStatus.FIXED, "The guard now handles the input.")));

    ArgumentCaptor<ReviewInput> reviewInputCaptor = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApi).review(reviewInputCaptor.capture());
    ReviewInput.CommentInput resolutionComment =
        reviewInputCaptor.getValue().comments.get("src/Example.java").getFirst();
    assertEquals(root.id, resolutionComment.inReplyTo);
    assertFalse(resolutionComment.unresolved);
  }

  @Test
  public void leavesUnboundOrNonRootCommentsUntouched() throws Exception {
    CommentInfo comment = new CommentInfo();
    comment.id = "ai-reply";
    comment.inReplyTo = "human-comment";
    comment.tag = "reviewai:concerns:review-1";
    comment.unresolved = true;
    when(changeApi.commentsRequest()).thenReturn(commentsRequest);
    when(commentsRequest.get()).thenReturn(Map.of("src/Example.java", List.of(comment)));
    changeSetData.setReviewSystemMessage("Main review message");

    client.setReviewAndGetPublishedCommentIds(
        change,
        List.of(),
        changeSetData,
        null,
        concernResponse(
            concern("ai-reply", ConcernStatus.FIXED, "The guard now handles the input.")));

    ArgumentCaptor<ReviewInput> reviewInputCaptor = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApi).review(reviewInputCaptor.capture());
    assertEquals("Main review message", reviewInputCaptor.getValue().message);
    assertNull(reviewInputCaptor.getValue().comments);
  }

  private static CommentInfo openTaggedComment(String commentId) {
    CommentInfo comment = new CommentInfo();
    comment.id = commentId;
    comment.tag = "reviewai:concerns:review-1";
    comment.unresolved = true;
    return comment;
  }

  private static CommentInfo reply(
      String commentId, String inReplyTo, boolean unresolved, String updated) {
    CommentInfo comment = new CommentInfo();
    comment.id = commentId;
    comment.inReplyTo = inReplyTo;
    comment.unresolved = unresolved;
    comment.setUpdated(Instant.parse(updated));
    return comment;
  }

  private static ReviewConcern concern(
      String commentId, ConcernStatus status, String statusReason) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId("concern-" + commentId);
    concern.setStatus(status);
    concern.setStatusReason(statusReason);
    concern.setPreviousCommentId(commentId);
    return concern;
  }

  private static AiResponseContent concernResponse(ReviewConcern... concerns) {
    ReviewerConcerns reviewer = new ReviewerConcerns();
    reviewer.setConcerns(List.of(concerns));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(reviewer));
    PendingReviewConcernUpdates updates = new PendingReviewConcernUpdates();
    updates.put("project~main~I1234567890", ledger);
    AiResponseContent response = new AiResponseContent("");
    response.setPendingConcernUpdates(updates);
    return response;
  }

  private static Map<String, List<CommentInfo>> existingComments(
      Map<String, List<CommentInfo>> comments) {
    Map<String, List<CommentInfo>> existingComments = new LinkedHashMap<>();
    comments.forEach(
        (filename, filenameComments) ->
            filenameComments.stream()
                .filter(comment -> comment.tag != null)
                .findFirst()
                .ifPresent(comment -> existingComments.put(filename, List.of(comment))));
    return existingComments;
  }

  private static Map<String, List<CommentInfo>> readPublishedComments() throws Exception {
    Type commentsType = new TypeToken<Map<String, List<CommentInfo>>>() {}.getType();
    String json =
        Files.readString(
            TestResourceLoader.getTestResourcePath()
                .resolve("__files/gerritPublishedConcernComments.json"));
    return OutputFormat.JSON.newGson().fromJson(json, commentsType);
  }
}
