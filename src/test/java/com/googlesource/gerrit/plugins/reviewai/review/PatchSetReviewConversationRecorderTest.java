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

package com.googlesource.gerrit.plugins.reviewai.review;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import com.googlesource.gerrit.plugins.reviewai.web.ReviewAgentConversationStore;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class PatchSetReviewConversationRecorderTest {
  private static final String PATCH_SET_TRIGGER_MESSAGE =
      "Patch set commit event triggered this ReviewAI request.";

  private ChangeSetData changeSetData;
  private ReviewAgentConversationStore conversationStore;
  private PatchSetReviewConversationRecorder recorder;
  private GerritChange change;
  private JsonObject fixture;

  @Before
  public void setUp() throws Exception {
    fixture =
        new com.google.gson.Gson()
            .fromJson(
                Files.readString(
                    TestResourceLoader.getTestResourcePath()
                        .resolve("__files/review/failureFeedback.json")),
                JsonObject.class);
    changeSetData = new ChangeSetData(1);
    conversationStore = mock(ReviewAgentConversationStore.class);
    recorder = new PatchSetReviewConversationRecorder(changeSetData, conversationStore);
    change = mock(GerritChange.class);
    when(change.getEventType()).thenReturn("comment-added");
    when(change.getFullChangeId()).thenReturn("test/project~main~I0123456789abcdef");
    when(change.getChangeNumber()).thenReturn(Optional.of(123));
  }

  @Test
  public void recordsDeferredReviewAsPatchSetTriggeredReview() {
    changeSetData.setDeferredReview(true);

    recorder.record(change, List.of(new ReviewBatch("Deferred review response")), null);

    ArgumentCaptor<JsonObject> turnCaptor = ArgumentCaptor.forClass(JsonObject.class);
    verify(conversationStore)
        .appendTurn(anyString(), anyString(), anyString(), turnCaptor.capture(), any());
    JsonObject turn = turnCaptor.getValue();
    assertEquals(
        PATCH_SET_TRIGGER_MESSAGE,
        turn.getAsJsonObject("user_input").get("user_question").getAsString());
    assertTrue(
        turn.getAsJsonObject("response")
            .getAsJsonArray("response_parts")
            .get(0)
            .getAsJsonObject()
            .get("text")
            .getAsString()
            .endsWith("Deferred review response"));
  }

  @Test
  public void doesNotRecordOrdinaryCommentReviewAsPatchSetTriggeredReview() {
    ReviewBatch batch = new ReviewBatch("Comment response");
    recorder.record(change, List.of(batch), null);

    verify(conversationStore, never())
        .appendTurn(anyString(), anyString(), anyString(), any(), any());
    assertEquals(batch.getContent(), changeSetData.getReviewStatusMessage());
  }

  @Test
  public void preservesExistingSystemStatusWithoutRepeatingTheError() {
    String error = fixture.get("connectionMessage").getAsString();
    changeSetData.setReviewSystemMessage(error);
    changeSetData.setReviewStatusMessage(error);

    recorder.record(change, List.of(), null);

    assertEquals(error, changeSetData.getReviewStatusMessage());
  }

  @Test
  public void preservesDetailedSystemStatusWithoutPrependingThePublicError() {
    String error = fixture.get("connectionMessage").getAsString();
    String details = error + "\n\n" + fixture.get("failure").getAsString();
    changeSetData.setReviewSystemMessage(error);
    changeSetData.setReviewStatusMessage(details);

    recorder.record(change, List.of(), null);

    assertEquals(details, changeSetData.getReviewStatusMessage());
    assertEquals(error, changeSetData.getReviewSystemMessage());
  }

  @Test
  public void combinesPublishedReviewWithAdditionalStatusDetails() {
    String response = fixture.get("finding").getAsString();
    String details = fixture.get("failure").getAsString();
    changeSetData.setReviewStatusMessage(details);

    recorder.record(change, List.of(new ReviewBatch(response)), null);

    assertEquals(response + "\n\n" + details, changeSetData.getReviewStatusMessage());
  }

  @Test
  public void usesSystemMessageWhenThereIsNoExistingStatusOrReview() {
    String error = fixture.get("connectionMessage").getAsString();
    changeSetData.setReviewSystemMessage(error);

    recorder.record(change, List.of(), null);

    assertEquals(error, changeSetData.getReviewStatusMessage());
  }
}
