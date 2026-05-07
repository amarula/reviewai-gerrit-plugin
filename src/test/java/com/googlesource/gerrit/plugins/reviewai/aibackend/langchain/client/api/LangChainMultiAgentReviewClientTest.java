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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class LangChainMultiAgentReviewClientTest {

  @Test
  public void mergesSeparatePatchsetAndCommitMessageReviews() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(false);
    when(change.getFullChangeId()).thenReturn("change~1");

    AiResponseContent response = client.ask(changeSetData, change, "patch");

    assertNotNull(response.getReplies());
    assertEquals(2, response.getReplies().size());
    assertEquals(
        List.of(ReviewAssistantStages.REVIEW_CODE, ReviewAssistantStages.REVIEW_COMMIT_MESSAGE),
        client.recordedStages);
    assertEquals("body-REVIEW_COMMIT_MESSAGE", client.getRequestBody());
  }

  @Test
  public void forcedScopedReviewBypassesParallelSplit() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setForcedStagedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStages.REVIEW_COMMIT_MESSAGE);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(false);
    when(change.getFullChangeId()).thenReturn("change~1");

    AiResponseContent response = client.ask(changeSetData, change, "patch");

    assertNotNull(response.getReplies());
    assertEquals(1, response.getReplies().size());
    assertEquals(List.of(ReviewAssistantStages.REVIEW_COMMIT_MESSAGE), client.recordedStages);
    assertEquals("body-REVIEW_COMMIT_MESSAGE", client.getRequestBody());
  }

  private static class RecordingLangChainMultiAgentReviewClient
      extends LangChainMultiAgentReviewClient {
    private final List<ReviewAssistantStages> recordedStages = new ArrayList<>();

    RecordingLangChainMultiAgentReviewClient() {
      super(null, null, null, null, Runnable::run);
    }

    @Override
    protected ReviewRequestResult askSingleRequest(
        ChangeSetData changeSetData, GerritChange change, String patchSet) {
      ReviewAssistantStages stage = changeSetData.getReviewAssistantStage();
      recordedStages.add(stage);

      AiReplyItem reply = AiReplyItem.builder().reply(stage.name()).build();
      AiResponseContent response = new AiResponseContent("");
      response.setReplies(new ArrayList<>(List.of(reply)));

      return new ReviewRequestResult(response, "body-" + stage.name());
    }
  }
}
