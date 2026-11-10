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

package com.googlesource.gerrit.plugins.reviewai.listener;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAgentRequestStatusStore;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Locale;
import org.junit.Before;
import org.junit.Test;

public class ReviewAgentEventRequestStatusUpdaterTest extends TestBase {
  private JsonObject fixture;
  private ReviewAgentRequestStatusStore store;
  private ReviewAgentEventRequestStatusUpdater updater;
  private final ChangeSetData data = new ChangeSetData(100);

  @Before
  public void setUp() throws Exception {
    fixture =
        getGson()
            .fromJson(
                Files.readString(
                    TestResourceLoader.getTestResourcePath()
                        .resolve("__files/reviewagent/sidebar-request.json")),
                JsonObject.class);
    PluginDataHandler handler =
        new PluginDataHandler(
            tempFolder.getRoot().toPath().resolve("sidebar.data"), getTestReviewAiDb());
    store = new ReviewAgentRequestStatusStore(handler);
    PluginDataHandlerProvider provider = mock(PluginDataHandlerProvider.class);
    when(provider.getChangeScope()).thenReturn(handler);
    Configuration config = mock(Configuration.class);
    when(config.getLocaleDefault()).thenReturn(Locale.ENGLISH);
    GerritChange change = mock(GerritChange.class);
    when(change.getPatchSetEvent())
        .thenReturn(
            new CommentAddedEvent(
                new Change(CHANGE_ID, Change.id(1), Account.id(10), BRANCH_NAME, Instant.now())));
    updater = new ReviewAgentEventRequestStatusUpdater(config, data, change, provider);
    store.pending(text("requestId"), text("prompt"));
    // Timed-out requests can still be pending when a new sidebar message is sent.
    store.pending(text("otherRequestId"), text("prompt"));
    data.setReviewStatusMessage(text("response"));
  }

  @Test
  public void resolvesRequestBoundAfterEventIntake() {
    var pending = updater.getPendingRequest(text("messageId"));
    store.move(text("requestId"), text("messageId"));

    pending.completeReview();

    assertCompleted();
  }

  @Test
  public void preservesCompletionBeforeRestHandlerBindsRequest() {
    updater.getPendingRequest(text("messageId")).completeReview();

    store.move(text("requestId"), text("messageId"));

    assertCompleted();
  }

  @Test
  public void preservesFailureBeforeRestHandlerBindsRequest() {
    updater.getPendingRequest(text("messageId")).fail(text("failure"));
    store.move(text("requestId"), text("messageId"));

    assertEquals(ReviewAgentRequestStatusStore.STATUS_FAILED, store.get(text("requestId")).status);
    assertEquals(text("failure"), store.get(text("messageId")).responseText);
    assertOtherPending();
  }

  @Test
  public void completingTheSameEventTwiceDoesNotCompleteAnotherRequest() {
    store.move(text("requestId"), text("messageId"));
    updater.getPendingRequest(text("messageId")).completeReview();
    updater.getPendingRequest(text("messageId")).completeReview();

    assertCompleted();
  }

  private void assertCompleted() {
    assertEquals(
        ReviewAgentRequestStatusStore.STATUS_COMPLETED, store.get(text("messageId")).status);
    assertEquals(text("response"), store.get(text("messageId")).responseText);
    assertEquals(text("response"), store.get(text("requestId")).responseText);
    assertOtherPending();
  }

  private void assertOtherPending() {
    assertEquals(
        ReviewAgentRequestStatusStore.STATUS_PENDING, store.get(text("otherRequestId")).status);
  }

  private String text(String key) {
    return fixture.get(key).getAsString();
  }
}
