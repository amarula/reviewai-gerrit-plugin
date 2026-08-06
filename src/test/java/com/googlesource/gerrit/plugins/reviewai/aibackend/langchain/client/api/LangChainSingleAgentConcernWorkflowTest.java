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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;

public class LangChainSingleAgentConcernWorkflowTest {
  private static final String FIRST_REVIEW_RESPONSE =
      "__files/langchain/singleAgentFirstReviewResponse.json";
  private static final String CONCERN_REVIEW_RESPONSE =
      "__files/langchain/singleAgentConcernReviewResponse.json";
  private static final String NEW_ISSUES_RESPONSE =
      "__files/langchain/singleAgentNewIssuesResponse.json";
  private static final String INCREMENTAL_PATCH =
      "__files/langchain/newIssueIncrementalPatch.txt";
  private static final String FULL_PATCH =
      "__files/langchain/newIssueFullPatch.txt";
  private static final String CHANGE_ID = "project~change-1";

  @Test
  public void firstReviewKeepsSingleRequestAndInitializesLedger() throws Exception {
    TestClient client = new TestClient();
    ChangeSetData data = new ChangeSetData(1);

    AiResponseContent response =
        client.ask(data, change(false), readTestResource(FULL_PATCH));

    assertEquals(List.of(ReviewAssistantStage.REVIEW_CODE), client.stages);
    assertTrue(response.getReplies().getFirst().isRepeated());
    assertNotNull(response.getReplies().getFirst().getConcernId());
    ReviewerConcerns stored = pendingLedger(response).getReviewers().getFirst();
    assertEquals(ConcernReviewerId.Kind.SINGLE_AGENT, stored.getReviewer().getKind());
    assertEquals("PATCHSET", stored.getReviewer().getName());
    assertEquals(
        response.getReplies().getFirst().getConcernId(),
        stored.getConcerns().getFirst().getId());
    assertTrue(Boolean.TRUE.equals(stored.getConcerns().getFirst().getRepeated()));
  }

  @Test
  public void subsequentReviewRunsSerialStagesAndPublishesPresentConcernsAsRepeated()
      throws Exception {
    TestClient client = new TestClient();
    ChangeSetData data = new ChangeSetData(1);
    data.setPreviousReviewConcernLedger(previousLedger());
    data.setIncrementalPatchSet(readTestResource(INCREMENTAL_PATCH));

    AiResponseContent response =
        client.ask(data, change(false), readTestResource(FULL_PATCH));

    assertEquals(
        List.of(ReviewAssistantStage.REVIEW_CONCERNS, ReviewAssistantStage.FIND_NEW_ISSUES),
        client.stages);
    assertEquals(
        readTestResource(INCREMENTAL_PATCH),
        client.concernData.getConcernWorkflowInput().getIncrementalPatch());
    assertEquals(
        readTestResource(FULL_PATCH),
        client.concernData.getConcernWorkflowInput().getFullPatch());
    assertEquals(
        readTestResource(FULL_PATCH),
        client.finderData.getConcernWorkflowInput().getFullPatch());
    assertEquals(2, response.getReplies().size());

    var repeated = response.getReplies().getFirst();
    assertEquals("old-present", repeated.getConcernId());
    assertTrue(repeated.isRepeated());
    assertEquals("old-present", repeated.getRepetitionReplyId());
    assertEquals("The dereference remains reachable.", repeated.getRepeatedReason());

    var newIssue = response.getReplies().get(1);
    assertFalse(newIssue.isRepeated());
    assertNotEquals("old-present", newIssue.getConcernId());
    assertNull(newIssue.getRepetitionReplyId());

    List<ReviewConcern> stored = pendingLedger(response).getReviewers().getFirst().getConcerns();
    assertEquals(3, stored.size());
    assertEquals(ConcernStatus.PRESENT, stored.get(0).getStatus());
    assertEquals(ConcernStatus.FIXED, stored.get(1).getStatus());
    assertEquals(newIssue.getConcernId(), stored.get(2).getId());
    assertEquals("new issue finder request", client.getRequestBody());
  }

  @Test
  public void emptyIncrementalPatchSkipsNewIssueFinderAndPreservesReviewedConcerns()
      throws Exception {
    TestClient client = new TestClient();
    ChangeSetData data = new ChangeSetData(1);
    data.setPreviousReviewConcernLedger(previousLedger());
    data.setIncrementalPatchSet("");

    AiResponseContent response =
        client.ask(data, change(false), readTestResource(FULL_PATCH));

    assertEquals(List.of(ReviewAssistantStage.REVIEW_CONCERNS), client.stages);
    assertEquals(1, response.getReplies().size());
    assertEquals("old-present", response.getReplies().getFirst().getConcernId());
    assertTrue(response.getReplies().getFirst().isRepeated());
    assertEquals(2, pendingLedger(response).getReviewers().getFirst().getConcerns().size());
  }

  @Test
  public void commentRequestDoesNotEnterConcernWorkflow() throws Exception {
    TestClient client = new TestClient();
    ChangeSetData data = new ChangeSetData(1);
    data.setPreviousReviewConcernLedger(previousLedger());

    AiResponseContent response =
        client.ask(data, change(true), readTestResource(FULL_PATCH));

    assertEquals(List.of(ReviewAssistantStage.REVIEW_CODE), client.stages);
    assertNull(response.getPendingConcernUpdates());
  }

  private static ReviewConcernLedger pendingLedger(AiResponseContent response) {
    return response
        .getPendingConcernUpdates()
        .get(CHANGE_ID)
        .orElseThrow();
  }

  private static ReviewConcernLedger previousLedger() {
    ReviewerConcerns concerns = new ReviewerConcerns();
    concerns.setReviewer(
        new ConcernReviewerId(ConcernReviewerId.Kind.SINGLE_AGENT, "PATCHSET"));
    concerns.setConcerns(
        List.of(
            concern("old-present", ConcernStatus.PRESENT, "Old dereference"),
            concern("old-fixed", ConcernStatus.PRESENT, "Old missing guard")));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(concerns));
    return ledger;
  }

  private static ReviewConcern concern(String id, ConcernStatus status, String reply) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId(id);
    concern.setStatus(status);
    concern.setReply(reply);
    concern.setDescription(reply);
    return concern;
  }

  private static GerritChange change(boolean commentEvent) {
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(commentEvent);
    when(change.getFullChangeId()).thenReturn(CHANGE_ID);
    return change;
  }

  private static String readTestResource(String resource) throws IOException {
    try (var stream =
        LangChainSingleAgentConcernWorkflowTest.class
            .getClassLoader()
            .getResourceAsStream(resource)) {
      if (stream == null) {
        throw new IOException("Missing test resource: " + resource);
      }
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static final class TestClient extends LangChainClient {
    private final List<ReviewAssistantStage> stages = new ArrayList<>();
    private final Map<ReviewAssistantStage, String> patches =
        new EnumMap<>(ReviewAssistantStage.class);
    private ChangeSetData concernData;
    private ChangeSetData finderData;

    private TestClient() {
      super(configuration(), null, null, null);
    }

    @Override
    protected RawReviewRequestResult askSingleRawRequest(
        ChangeSetData changeSetData, GerritChange change, String patchSet) throws IOException {
      ReviewAssistantStage stage = changeSetData.getReviewAssistantStage();
      stages.add(stage);
      patches.put(stage, patchSet);
      if (stage == ReviewAssistantStage.FIND_NEW_ISSUES) {
        finderData = changeSetData;
        return rawReviewRequestResult(
            readTestResource(NEW_ISSUES_RESPONSE), "new issue finder request");
      }
      if (stage == ReviewAssistantStage.REVIEW_CONCERNS) {
        concernData = changeSetData;
        return rawReviewRequestResult(
            readTestResource(CONCERN_REVIEW_RESPONSE), "concern review request");
      }
      return rawReviewRequestResult(
          readTestResource(FIRST_REVIEW_RESPONSE), "first review request");
    }

    private static Configuration configuration() {
      Configuration config = mock(Configuration.class);
      when(config.getAgentSpecializationLevel())
          .thenReturn(AgentSpecializationLevel.SINGLE_AGENT);
      when(config.getCodeContextPolicy()).thenReturn(CodeContextPolicies.NONE);
      when(config.resolveMockAiFallbackRoute(anyString())).thenReturn(Optional.empty());
      return config;
    }
  }
}
