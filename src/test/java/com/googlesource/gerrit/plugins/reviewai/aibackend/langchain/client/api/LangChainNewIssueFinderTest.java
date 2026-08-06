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
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    LangChainNewIssueFinder finder =
        new LangChainNewIssueFinder(configuration(CodeContextPolicies.NONE));
    CapturedRequest capturedRequest = new CapturedRequest();
    ReviewerConcerns concerns = reviewerConcerns();
    String incrementalPatch = readTestResource(INCREMENTAL_PATCH_RESOURCE);
    String fullPatch = readTestResource(FULL_PATCH_RESOURCE);

    String result =
        finder.find(
            new ChangeSetData(1),
            mock(GerritChange.class),
            concerns,
            incrementalPatch,
            fullPatch,
            (requestData, change, patchSet) -> {
              capturedRequest.requestData = requestData;
              capturedRequest.patchSet = patchSet;
              return readTestResource(RESPONSE_RESOURCE);
            });

    assertEquals(readTestResource(RESPONSE_RESOURCE), result);
    assertEquals(
        ReviewAssistantStage.FIND_NEW_ISSUES,
        capturedRequest.requestData.getReviewAssistantStage());
    assertTrue(capturedRequest.requestData.getForcedStagedReview());
    assertSame(
        concerns,
        capturedRequest.requestData.getConcernWorkflowInput().getConcerns());
    assertEquals(
        incrementalPatch,
        capturedRequest.requestData.getConcernWorkflowInput().getIncrementalPatch());
    assertEquals(
        fullPatch, capturedRequest.requestData.getConcernWorkflowInput().getFullPatch());
    assertEquals("", capturedRequest.patchSet);
  }

  @Test
  public void onDemandPolicyOmitsFullPatchFromPayload() throws Exception {
    LangChainNewIssueFinder finder =
        new LangChainNewIssueFinder(configuration(CodeContextPolicies.ON_DEMAND));
    CapturedRequest capturedRequest = new CapturedRequest();

    finder.find(
        new ChangeSetData(1),
        mock(GerritChange.class),
        reviewerConcerns(),
        readTestResource(INCREMENTAL_PATCH_RESOURCE),
        readTestResource(FULL_PATCH_RESOURCE),
        (requestData, change, patchSet) -> {
          capturedRequest.requestData = requestData;
          capturedRequest.patchSet = patchSet;
          return readTestResource(RESPONSE_RESOURCE);
        });

    assertNull(capturedRequest.requestData.getConcernWorkflowInput().getFullPatch());
  }

  @Test
  public void emptyIncrementalPatchSkipsNewIssueFinderRequest() throws Exception {
    LangChainNewIssueFinder finder = new LangChainNewIssueFinder(configuration(CodeContextPolicies.NONE));

    String result =
        finder.find(
            new ChangeSetData(1),
            mock(GerritChange.class),
            reviewerConcerns(),
            "",
            readTestResource(FULL_PATCH_RESOURCE),
            (requestData, change, patchSet) -> {
              fail("New issue finder must not run for an empty incremental patch");
              return null;
            });

    assertNull(result);
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

  private static Configuration configuration(CodeContextPolicies policy) {
    Configuration config = mock(Configuration.class);
    when(config.getCodeContextPolicy()).thenReturn(policy);
    return config;
  }

  private static final class CapturedRequest {
    private ChangeSetData requestData;
    private String patchSet;
  }
}
