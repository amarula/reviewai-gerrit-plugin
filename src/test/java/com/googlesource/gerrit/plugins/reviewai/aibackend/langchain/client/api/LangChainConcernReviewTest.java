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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.metrics.cost.AiCostTracker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class LangChainConcernReviewTest {
  private static final String RESPONSE_RESOURCE =
      "__files/langchain/concernReviewResponse.json";
  private static final String INCREMENTAL_PATCH_RESOURCE =
      "__files/langchain/newIssueIncrementalPatch.txt";
  private static final String FULL_PATCH_RESOURCE =
      "__files/langchain/newIssueFullPatch.txt";

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
            (requestData, change, patchSet) -> {
              capturedRequest.requestData = requestData;
              capturedRequest.patchSet = patchSet;
              return readTestResource(RESPONSE_RESOURCE);
            });

    assertSame(reviewerId, result.getReviewer());
    assertEquals(ConcernStatus.FIXED, result.getConcerns().getFirst().getStatus());
    assertEquals(
        "A null value reaches the dereference.",
        result.getConcerns().getFirst().getDescription());
    assertEquals(ConcernStatus.PRESENT, existing.getStatus());
    assertEquals(
        ReviewAssistantStage.REVIEW_CONCERNS,
        capturedRequest.requestData.getReviewAssistantStage());
    assertTrue(capturedRequest.requestData.getForcedStagedReview());
    assertSame(
        reviewerConcerns,
        capturedRequest.requestData.getConcernWorkflowInput().getConcerns());
    assertEquals(
        incrementalPatch,
        capturedRequest.requestData.getConcernWorkflowInput().getIncrementalPatch());
    assertEquals(
        fullPatch, capturedRequest.requestData.getConcernWorkflowInput().getFullPatch());
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
        (requestData, change, patchSet) -> {
          capturedRequest.requestData = requestData;
          capturedRequest.patchSet = patchSet;
          return readTestResource(RESPONSE_RESOURCE);
        });

    assertNull(capturedRequest.requestData.getConcernWorkflowInput().getFullPatch());
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
    try (var stream = LangChainConcernReviewTest.class.getClassLoader().getResourceAsStream(resource)) {
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
