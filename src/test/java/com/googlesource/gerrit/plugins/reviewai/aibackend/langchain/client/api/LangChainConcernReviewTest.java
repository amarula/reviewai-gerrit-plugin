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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernWorkflowInput;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackClassificationInput.ThreadMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiCostTracker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class LangChainConcernReviewTest {
  private static final String RESPONSE_RESOURCE = "__files/langchain/concernReviewResponse.json";
  private static final String INCREMENTAL_PATCH_RESOURCE =
      "__files/langchain/newIssueIncrementalPatch.txt";
  private static final String FULL_PATCH_RESOURCE = "__files/langchain/newIssueFullPatch.txt";
  private static final String THREAD_CONTEXT_RESOURCE =
      "__files/langchain/concernThreadContext.json";

  @Test
  public void providesLatestUserAndAiExchangeOnlyFromTheConcernThread() throws Exception {
    ThreadContextFixture fixture = threadContextFixture();

    ConcernWorkflowInput input =
        reviewWithContext(fixture, fixture.comments, readTestResource(RESPONSE_RESOURCE));

    assertEquals(fixture.expectedThreadContext, input.getThreadContext());
    assertEquals(
        getGson().toJsonTree(fixture.expectedThreadContext),
        getGson().toJsonTree(input).getAsJsonObject().get("thread_context"));
  }

  @Test
  public void providesNewerUnansweredUserMessageInsteadOfAnOlderAcknowledgement() throws Exception {
    ThreadContextFixture fixture = threadContextFixture();
    List<GerritComment> comments = new ArrayList<>(fixture.comments);
    comments.add(fixture.unansweredMessage);

    ConcernWorkflowInput input =
        reviewWithContext(fixture, comments, readTestResource(RESPONSE_RESOURCE));

    assertEquals(fixture.unansweredContext, input.getThreadContext());
  }

  @Test
  public void omitsContextWhenTheConcernCommentCannotBeFound() throws Exception {
    ThreadContextFixture fixture = threadContextFixture();
    List<GerritComment> comments =
        fixture.comments.stream()
            .filter(
                comment ->
                    !comment
                        .getId()
                        .equals(fixture.concerns.getConcerns().getFirst().getPreviousCommentId()))
            .toList();

    ConcernWorkflowInput input =
        reviewWithContext(fixture, comments, readTestResource(RESPONSE_RESOURCE));

    assertTrue(input.getThreadContext().isEmpty());
  }

  @Test
  public void aiAcknowledgementDoesNotAuthorizeConcernDismissal() throws Exception {
    ThreadContextFixture fixture = threadContextFixture();

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                reviewWithContext(
                    fixture, fixture.comments, getGson().toJson(fixture.dismissedResponse)));

    assertEquals(fixture.authorizationError, failure.getMessage());
  }

  private static ConcernWorkflowInput reviewWithContext(
      ThreadContextFixture fixture, List<GerritComment> comments, String response)
      throws Exception {
    HashMap<String, GerritComment> commentsById = new HashMap<>();
    comments.forEach(comment -> commentsById.put(comment.getId(), comment));
    CommentData commentData = new CommentData(List.of(), commentsById, new HashMap<>());
    CapturedRequest captured = new CapturedRequest();
    concernReviewer(mock(Configuration.class))
        .review(
            new ChangeSetData(fixture.aiAccountId),
            mock(GerritChange.class),
            fixture.concerns,
            readTestResource(INCREMENTAL_PATCH_RESOURCE),
            readTestResource(FULL_PATCH_RESOURCE),
            commentData,
            (requestData, change, patchSet) -> {
              captured.requestData = requestData;
              return response;
            });
    return captured.requestData.getConcernWorkflowInput();
  }

  private static ThreadContextFixture threadContextFixture() throws IOException {
    return getGson()
        .fromJson(readTestResource(THREAD_CONTEXT_RESOURCE), ThreadContextFixture.class);
  }

  private static final class ThreadContextFixture {
    private int aiAccountId;
    private ReviewerConcerns concerns;
    private List<GerritComment> comments;
    private Map<String, List<ThreadMessage>> expectedThreadContext;
    private GerritComment unansweredMessage;
    private Map<String, List<ThreadMessage>> unansweredContext;
    private ReviewerConcerns dismissedResponse;
    private String authorizationError;
  }

  @Test
  public void nonePolicyProvidesSamePatchContextAsNewIssueFinder() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.NONE);
    LangChainConcernReviewer concernReviewer = concernReviewer(config);
    CapturedRequest capturedRequest = new CapturedRequest();
    ConcernReviewerId reviewerId =
        new ConcernReviewerId(ConcernReviewerId.Kind.SPECIALIZED_AGENT, "CORRECTNESS");
    ReviewConcern existing = new ReviewConcern();
    existing.setId("concern-1");
    existing.setStatus(ConcernStatus.PRESENT);
    existing.setDescription("A null value reaches the dereference.");
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setReviewer(reviewerId);
    reviewerConcerns.setConcerns(List.of(existing));

    String incrementalPatch = readTestResource(INCREMENTAL_PATCH_RESOURCE);
    String fullPatch = readTestResource(FULL_PATCH_RESOURCE);
    ReviewerConcerns result =
        concernReviewer.review(
            new ChangeSetData(1),
            mock(GerritChange.class),
            reviewerConcerns,
            incrementalPatch,
            fullPatch,
            null,
            (requestData, change, patchSet) -> {
              capturedRequest.requestData = requestData;
              capturedRequest.patchSet = patchSet;
              return readTestResource(RESPONSE_RESOURCE);
            });

    assertSame(reviewerId, result.getReviewer());
    assertEquals(ConcernStatus.FIXED, result.getConcerns().getFirst().getStatus());
    assertEquals(
        "A null value reaches the dereference.", result.getConcerns().getFirst().getDescription());
    assertEquals(ConcernStatus.PRESENT, existing.getStatus());
    assertEquals(
        ReviewAssistantStage.REVIEW_CONCERNS,
        capturedRequest.requestData.getReviewAssistantStage());
    assertTrue(capturedRequest.requestData.getForcedStagedReview());
    assertSame(
        reviewerConcerns, capturedRequest.requestData.getConcernWorkflowInput().getConcerns());
    assertEquals(
        incrementalPatch,
        capturedRequest.requestData.getConcernWorkflowInput().getIncrementalPatch());
    assertEquals(fullPatch, capturedRequest.requestData.getConcernWorkflowInput().getFullPatch());
    assertEquals("", capturedRequest.patchSet);
  }

  @Test
  public void onDemandPolicyOmitsFullPatchLikeNewIssueFinder() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.ON_DEMAND);
    LangChainConcernReviewer concernReviewer = concernReviewer(config);
    CapturedRequest capturedRequest = new CapturedRequest();
    ReviewerConcerns concerns = new ReviewerConcerns();
    concerns.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SPECIALIZED_AGENT, "CORRECTNESS"));
    ReviewConcern concern = new ReviewConcern();
    concern.setId("concern-1");
    concerns.setConcerns(List.of(concern));

    concernReviewer.review(
        new ChangeSetData(1),
        mock(GerritChange.class),
        concerns,
        readTestResource(INCREMENTAL_PATCH_RESOURCE),
        readTestResource(FULL_PATCH_RESOURCE),
        null,
        (requestData, change, patchSet) -> {
          capturedRequest.requestData = requestData;
          capturedRequest.patchSet = patchSet;
          return readTestResource(RESPONSE_RESOURCE);
        });

    assertNull(capturedRequest.requestData.getConcernWorkflowInput().getFullPatch());
  }

  @Test
  public void providesCurrentConditionLabelsToConcernReviewer() throws Exception {
    Configuration config = mock(Configuration.class);
    LangChainConcernReviewer concernReviewer = concernReviewer(config);
    ReviewerConcerns concerns = new ReviewerConcerns();
    concerns.setReviewer(new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "PATCHSET"));
    ReviewConcern concern = new ReviewConcern();
    concern.setId("concern-1");
    concerns.setConcerns(List.of(concern));
    Map<String, GerritConditionLabel> conditionLabels =
        Map.of("Verified", new GerritConditionLabel(List.of((short) 1), "CI verification"));
    ChangeSetData changeSetData = new ChangeSetData(1);
    changeSetData.setConditionLabels(conditionLabels);
    CapturedRequest capturedRequest = new CapturedRequest();

    concernReviewer.review(
        changeSetData,
        mock(GerritChange.class),
        concerns,
        readTestResource(INCREMENTAL_PATCH_RESOURCE),
        readTestResource(FULL_PATCH_RESOURCE),
        null,
        (requestData, change, patchSet) -> {
          capturedRequest.requestData = requestData;
          return readTestResource(RESPONSE_RESOURCE);
        });

    assertEquals(conditionLabels, capturedRequest.requestData.getConditionLabels());
  }

  private static LangChainConcernReviewer concernReviewer(Configuration config) {
    return new LangChainConcernReviewer(
        config,
        List.of(),
        false,
        null,
        mock(AiCostTracker.class),
        responseFormat -> responseFormat);
  }

  private static String readTestResource(String resource) throws IOException {
    try (var stream =
        LangChainConcernReviewTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IOException("Missing test resource: " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static final class CapturedRequest {
    private ChangeSetData requestData;
    private String patchSet;
  }
}
