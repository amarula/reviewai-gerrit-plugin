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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.Test;

public class LangChainNewIssueFinderTest {
  private static final String RESPONSE_RESOURCE =
      "__files/langchain/newIssueFinderResponse.json";
  private static final String INCREMENTAL_PATCH_RESOURCE =
      "__files/langchain/newIssueIncrementalPatch.txt";
  private static final String FULL_PATCH_RESOURCE =
      "__files/langchain/newIssueFullPatch.txt";

  @Test
  public void nonePolicyProvidesFullPatchAsSupportingContext() throws Exception {
    TestClient client = new TestClient(CodeContextPolicies.NONE);
    ReviewerConcerns concerns = reviewerConcerns();
    String incrementalPatch = readTestResource(INCREMENTAL_PATCH_RESOURCE);
    String fullPatch = readTestResource(FULL_PATCH_RESOURCE);

    AiResponseContent response =
        client.runNewIssueFinder(concerns, incrementalPatch, fullPatch);

    assertEquals(1, response.getReplies().size());
    assertEquals(ReviewAssistantStage.FIND_NEW_ISSUES, client.requestData.getReviewAssistantStage());
    assertTrue(client.requestData.getForcedStagedReview());
    assertSame(
        concerns,
        client.requestData.getConcernWorkflowInput().getConcerns());
    assertEquals(
        incrementalPatch,
        client.requestData.getConcernWorkflowInput().getIncrementalPatch());
    assertEquals(fullPatch, client.requestData.getConcernWorkflowInput().getFullPatch());
  }

  @Test
  public void onDemandPolicyOmitsFullPatchFromPayload() throws Exception {
    TestClient client = new TestClient(CodeContextPolicies.ON_DEMAND);

    client.runNewIssueFinder(
        reviewerConcerns(),
        readTestResource(INCREMENTAL_PATCH_RESOURCE),
        readTestResource(FULL_PATCH_RESOURCE));

    assertNull(client.requestData.getConcernWorkflowInput().getFullPatch());
  }

  private static ReviewerConcerns reviewerConcerns() {
    ReviewerConcerns concerns = new ReviewerConcerns();
    concerns.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "PATCHSET"));
    return concerns;
  }

  private static String readTestResource(String resource) throws IOException {
    try (var stream =
        LangChainNewIssueFinderTest.class.getClassLoader().getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IOException("Missing test resource: " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static final class TestClient extends LangChainClient {
    private final String response;
    private ChangeSetData requestData;

    private TestClient(CodeContextPolicies policy) throws IOException {
      super(configuration(policy), null, null, null);
      response = readTestResource(RESPONSE_RESOURCE);
    }

    private AiResponseContent runNewIssueFinder(
        ReviewerConcerns concerns, String incrementalPatch, String fullPatch)
        throws Exception {
      ReviewRequestResult result =
          findNewIssueReplies(
              new ChangeSetData(1),
              mock(GerritChange.class),
              concerns,
              incrementalPatch,
              fullPatch);
      return result.getResponseContent();
    }

    @Override
    protected RawReviewRequestResult askSingleRawRequest(
        ChangeSetData changeSetData, GerritChange change, String patchSet) {
      requestData = changeSetData;
      return rawReviewRequestResult(response, "new issue finder request");
    }

    private static Configuration configuration(CodeContextPolicies policy) {
      Configuration config = mock(Configuration.class);
      when(config.getCodeContextPolicy()).thenReturn(policy);
      when(config.resolveMockAiFallbackRoute(anyString())).thenReturn(Optional.empty());
      return config;
    }
  }
}
