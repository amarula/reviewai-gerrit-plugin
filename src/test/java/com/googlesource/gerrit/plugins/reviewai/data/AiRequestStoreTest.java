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

package com.googlesource.gerrit.plugins.reviewai.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.Before;
import org.junit.Test;

public class AiRequestStoreTest extends TestBase {
  private static final String CHANGE_ID = "project~branch~change";
  private static final String OWNER = "worker-1";
  private static final long LEASE = 10_000L;

  private AiRequestStore store;

  @Before
  public void setUp() {
    store = new AiRequestStore(getTestReviewAiDb());
  }

  @Test
  public void queuesAndClaimsMessagesInFifoOrder() {
    store.admit(message("request-1", "event-1"));
    store.admit(message("request-2", "event-2"));

    AiRequest first = store.claimNext(CHANGE_ID, OWNER, LEASE).orElseThrow();

    assertEquals("request-1", first.requestId());
    assertEquals(AiRequest.State.RUNNING, first.state());
    assertTrue(store.complete(first.requestId(), OWNER, null));

    AiRequest second = store.claimNext(CHANGE_ID, OWNER, LEASE).orElseThrow();

    assertEquals("request-2", second.requestId());
  }

  @Test
  public void rejectsReviewWhenQueuedRequestOccupiesChange() {
    store.admit(message("message-1", "event-1"));

    AiRequestStore.Admission admission =
        store.admit(review("review-1", "event-2"));

    assertFalse(admission.duplicate());
    assertEquals(AiRequest.State.REJECTED, admission.request().state());
    assertEquals("message-1", store.claimNext(CHANGE_ID, OWNER, LEASE).orElseThrow().requestId());
  }

  @Test
  public void requestsRunningReviewSupersessionAndRejectsIncomingReview() {
    store.admit(review("review-1", "patch-set-1"));
    assertEquals(
        "review-1", store.claimNext(CHANGE_ID, OWNER, LEASE).orElseThrow().requestId());

    AiRequest requested =
        store
            .requestSupersession(CHANGE_ID, "Superseded by patch set 2")
            .orElseThrow();
    AiRequestStore.Admission incoming = store.admit(review("review-2", "patch-set-2"));

    assertEquals(AiRequest.State.SUPERSEDE_REQUESTED, requested.state());
    assertEquals(AiRequest.State.REJECTED, incoming.request().state());
    assertTrue(store.renewLease("review-1", OWNER, LEASE + 1));
    assertTrue(store.claimNext(CHANGE_ID, OWNER, LEASE).isEmpty());
    assertTrue(store.complete("review-1", OWNER, null));
    assertEquals(AiRequest.State.SUPERSEDED, store.get("review-1").orElseThrow().state());
    assertTrue(store.claimNext(CHANGE_ID, OWNER, LEASE).isEmpty());
  }

  @Test
  public void deduplicatesReplayedSourceEvent() {
    AiRequestStore.Admission initial = store.admit(message("request-1", "event-1"));

    AiRequestStore.Admission replay = store.admit(message("request-2", "event-1"));

    assertTrue(replay.duplicate());
    assertEquals(initial.request(), replay.request());
    assertEquals(1, store.listByChange(CHANGE_ID).size());
  }

  @Test
  public void staleOwnerCannotCompleteRequest() {
    store.admit(message("request-1", "event-1"));
    assertEquals(
        "request-1", store.claimNext(CHANGE_ID, OWNER, LEASE).orElseThrow().requestId());

    assertFalse(store.complete("request-1", "stale-worker", null));
    assertEquals(AiRequest.State.RUNNING, store.get("request-1").orElseThrow().state());
    assertTrue(store.complete("request-1", OWNER, null));
  }

  @Test
  public void failureStoresReasonAndReleasesChangeLane() {
    store.admit(message("request-1", "event-1"));
    store.admit(message("request-2", "event-2"));
    assertEquals(
        "request-1", store.claimNext(CHANGE_ID, OWNER, LEASE).orElseThrow().requestId());
    String failureReason = AiRequest.State.FAILED.name();

    assertTrue(store.fail("request-1", OWNER, failureReason));

    AiRequest failed = store.get("request-1").orElseThrow();
    assertEquals(AiRequest.State.FAILED, failed.state());
    assertEquals(failureReason, failed.resultText());
    assertEquals("request-2", store.claimNext(CHANGE_ID, OWNER, LEASE).orElseThrow().requestId());
  }

  @Test
  public void abandonsExpiredRequestAndMakesNextMessageClaimable() {
    store.admit(message("request-1", "event-1"));
    store.admit(message("request-2", "event-2"));
    assertEquals(
        "request-1", store.claimNext(CHANGE_ID, OWNER, LEASE).orElseThrow().requestId());

    assertEquals(1, store.abandonExpired(LEASE, "expired"));
    assertEquals(AiRequest.State.ABANDONED, store.get("request-1").orElseThrow().state());
    assertEquals(
        "request-2", store.claimNext(CHANGE_ID, "worker-2", LEASE + 1).orElseThrow().requestId());
  }

  @Test
  public void queuedMessagesSurviveStoreRecreation() {
    store.admit(message("request-1", "event-1"));

    AiRequestStore recreated = new AiRequestStore(getTestReviewAiDb());

    assertEquals(
        "request-1", recreated.claimNext(CHANGE_ID, OWNER, LEASE).orElseThrow().requestId());
  }

  @Test
  public void requestsForDifferentChangesCanRunAtTheSameTime() {
    store.admit(message("request-1", "event-1"));
    store.admit(message("request-2", "other-event", "other-change"));

    assertTrue(store.claimNext(CHANGE_ID, OWNER, LEASE).isPresent());
    assertTrue(store.claimNext("other-change", OWNER, LEASE).isPresent());
  }

  @Test
  public void concurrentReviewsProduceOneQueuedAndOneRejectedRequest() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<AiRequestStore.Admission> first =
          executor.submit(() -> admitAfterSignal(review("review-1", "event-1"), ready, start));
      Future<AiRequestStore.Admission> second =
          executor.submit(() -> admitAfterSignal(review("review-2", "event-2"), ready, start));
      ready.await();
      start.countDown();

      List<AiRequest.State> states =
          List.of(first.get().request().state(), second.get().request().state());

      assertEquals(1, states.stream().filter(AiRequest.State.QUEUED::equals).count());
      assertEquals(1, states.stream().filter(AiRequest.State.REJECTED::equals).count());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void listsChangesWithQueuedWorkByOldestRequest() {
    store.admit(message("request-1", "event-1", "first-change"));
    store.admit(message("request-2", "event-2", "second-change"));

    assertEquals(List.of("first-change"), store.listQueuedChangeIds(1));
  }

  @Test
  public void doesNotListChangeWhileItsLaneIsActive() {
    store.admit(message("request-1", "event-1"));
    store.admit(message("request-2", "event-2"));
    assertEquals(
        "request-1", store.claimNext(CHANGE_ID, OWNER, LEASE).orElseThrow().requestId());

    assertTrue(store.listQueuedChangeIds(10).isEmpty());
  }

  @Test
  public void deletesAllRequestsAndLaneForChange() throws Exception {
    store.admit(message("request-1", "event-1"));
    store.admit(message("request-2", "event-2"));
    store.admit(message("request-3", "other-event", "other-change"));
    assertTrue(store.claimNext(CHANGE_ID, OWNER, LEASE).isPresent());

    store.deleteByChange(CHANGE_ID);
    assertTrue(store.claimNext(CHANGE_ID, OWNER, LEASE).isEmpty());

    assertTrue(store.listByChange(CHANGE_ID).isEmpty());
    assertFalse(store.hasQueuedRequest(CHANGE_ID));
    assertEquals(0, laneCount(CHANGE_ID));
    assertEquals(1, store.listByChange("other-change").size());
  }

  private AiRequestStore.Admission admitAfterSignal(
      AiRequestSubmission submission, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    start.await();
    return store.admit(submission);
  }

  private int laneCount(String changeId) throws Exception {
    try (Connection connection = getTestReviewAiDb().getConnection();
        PreparedStatement statement =
            connection.prepareStatement("SELECT COUNT(*) FROM ai_request_lanes WHERE change_id = ?")) {
      statement.setString(1, changeId);
      try (ResultSet results = statement.executeQuery()) {
        results.next();
        return results.getInt(1);
      }
    }
  }

  private AiRequestSubmission message(String requestId, String sourceEventId) {
    return message(requestId, sourceEventId, CHANGE_ID);
  }

  private AiRequestSubmission message(
      String requestId, String sourceEventId, String changeId) {
    return submission(
        requestId,
        sourceEventId,
        changeId,
        AiRequest.Kind.MESSAGE,
        AiRequest.AdmissionPolicy.QUEUE);
  }

  private AiRequestSubmission review(String requestId, String sourceEventId) {
    return submission(
        requestId,
        sourceEventId,
        CHANGE_ID,
        AiRequest.Kind.REVIEW,
        AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED);
  }

  private AiRequestSubmission submission(
      String requestId,
      String sourceEventId,
      String changeId,
      AiRequest.Kind kind,
      AiRequest.AdmissionPolicy policy) {
    return new AiRequestSubmission(
        requestId,
        changeId,
        sourceEventId,
        kind,
        policy,
        GsonUtils.getGson().toJson(Map.of()));
  }
}
