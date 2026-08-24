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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.reflect.TypeToken;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackStore;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class AiReviewThreadsTest {
  private static final int AI_ACCOUNT_ID = 7;

  private List<GerritComment> comments;

  @Before
  public void setUp() throws IOException {
    String json =
        Files.readString(
            TestResourceLoader.getTestResourcePath()
                .resolve("__files/gerritCommentThreads.json"));
    comments = getGson().fromJson(json, new TypeToken<List<GerritComment>>() {}.getType());
    setAuthor("ai-concern", AI_ACCOUNT_ID, "ReviewAI");
    setAuthor("ai-ack", AI_ACCOUNT_ID, "ReviewAI");
    setAuthor("review-command", 42, "Alice");
    setAuthor("user-reply", 42, "Alice");
    setAuthor("sibling-reply", 43, "Bob");
  }

  @Test
  public void groupsEveryThreadContainingAnAiComment() {
    AiReviewThreads.Output output = AiReviewThreads.buildOutput(comments, AI_ACCOUNT_ID);

    assertEquals(8, output.totalComments);
    assertEquals(2, output.aiComments);
    assertEquals(1, output.totalThreads);

    AiReviewThreads.ThreadInfo thread = output.threads.get(0);
    assertEquals("review-command", thread.rootId);
    assertFalse(thread.rootIsAi);
    assertEquals(5, thread.size);
    assertTrue(thread.hasUserReply);
    assertEquals(
        List.of("review-command", "ai-concern", "user-reply", "ai-ack", "sibling-reply"),
        thread.comments.stream().map(comment -> comment.id).toList());
  }

  @Test
  public void annotatesThreadsOnlyThroughPublishedConcernCommentIds() {
    AiReviewThreads.Output output = AiReviewThreads.buildOutput(comments, AI_ACCOUNT_ID);

    AiReviewThreads.annotateWithLedger(output, ledgerForAiConcern());

    assertEquals("abc123", output.concernLedger.lastReviewedCommit);
    assertEquals("CORRECTNESS", output.concernLedger.reviewers.get(0).name);
    assertEquals("ai-concern", output.concernLedger.reviewers.get(0).concerns.get(0).previousCommentId);
    assertEquals(List.of("concern-1"), output.threads.get(0).concernIds);
  }

  @Test
  public void exposesFeedbackStateAndClassifierConcernRouting() {
    AiReviewThreads.Output output = AiReviewThreads.buildOutput(comments, AI_ACCOUNT_ID);
    AiReviewThreads.annotateWithLedger(output, ledgerForAiConcern());
    ReviewFeedbackMemory memory = new ReviewFeedbackMemory();
    memory.setGenericFeedback("Focus on resource lifecycles.");
    memory.setConcernFeedback(Map.of("concern-1", "The framework owns this connection."));

    AiReviewThreads.annotateWithFeedback(
        output,
        memory,
        List.of(new ReviewFeedbackStore.FeedbackComment("user-reply", "PENDING")));

    assertEquals("Focus on resource lifecycles.", output.feedbackMemory.genericFeedback);
    assertEquals("PENDING", output.feedbackComments.get(0).processingState);
    AiReviewThreads.ThreadComment reply =
        output.threads.get(0).comments.stream()
            .filter(comment -> "user-reply".equals(comment.id))
            .findFirst()
            .orElseThrow();
    assertEquals("PENDING", reply.feedbackState);
    assertEquals("concern-1", reply.threadConcernId);
  }

  private static ReviewConcernLedger ledgerForAiConcern() {
    ReviewConcern concern = new ReviewConcern();
    concern.setId("concern-1");
    concern.setOwnerAgent("CORRECTNESS");
    concern.setPreviousCommentId("ai-concern");
    ReviewerConcerns reviewer = new ReviewerConcerns();
    reviewer.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SPECIALIZED_AGENT, "CORRECTNESS"));
    reviewer.setConcerns(List.of(concern));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setLastReviewedCommit("abc123");
    ledger.setReviewers(List.of(reviewer));
    return ledger;
  }

  private void setAuthor(String id, int accountId, String name) {
    GerritComment.Author author = new GerritComment.Author();
    author.setAccountId(accountId);
    author.setName(name);
    comments.stream()
        .filter(comment -> id.equals(comment.getId()))
        .findFirst()
        .orElseThrow()
        .setAuthor(author);
  }
}
