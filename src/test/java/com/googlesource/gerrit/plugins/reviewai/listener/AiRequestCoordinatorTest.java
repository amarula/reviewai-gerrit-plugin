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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.AiRequestCancellation;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequest;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestStore;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestSubmission;
import com.googlesource.gerrit.plugins.reviewai.listener.AiRequestCoordinator.ProcessingOutcome;
import com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AiRequestCoordinatorTest extends TestBase {
  private static final String CHANGE_ID = "project~branch~change";
  private static final long LEASE_MILLIS = TimeUnit.MINUTES.toMillis(1);
  private static final long RECOVERY_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

  private AiRequestStore store;
  private ScheduledExecutorService requestExecutor;
  private ScheduledExecutorService leaseExecutor;
  private AiRequestCoordinator coordinator;

  @Before
  public void setUp() {
    store = new AiRequestStore(getTestReviewAiDb());
    requestExecutor = Executors.newScheduledThreadPool(2);
    leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    coordinator =
        new AiRequestCoordinator(
            store,
            requestExecutor,
            leaseExecutor,
            LEASE_MILLIS,
            RECOVERY_INTERVAL_MILLIS);
    coordinator.start(request -> ProcessingOutcome.COMPLETED);
  }

  @After
  public void tearDown() {
    coordinator.stop();
  }

  @Test
  public void processesRequestsForSameChangeOneAtATime() throws Exception {
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    CountDownLatch completed = new CountDownLatch(2);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximumActive = new AtomicInteger();
    List<String> processingOrder = new CopyOnWriteArrayList<>();

    coordinator.admit(
        message("request-1", "event-1"),
        request -> {
          enter(request, active, maximumActive, processingOrder);
          firstStarted.countDown();
          releaseFirst.await();
          leave(active, completed);
          return ProcessingOutcome.COMPLETED;
        });
    assertTrue(firstStarted.await(5, TimeUnit.SECONDS));

    coordinator.admit(
        message("request-2", "event-2"),
        request -> {
          enter(request, active, maximumActive, processingOrder);
          leave(active, completed);
          return ProcessingOutcome.COMPLETED;
        });

    assertEquals(List.of("request-1"), processingOrder);
    releaseFirst.countDown();
    assertTrue(completed.await(5, TimeUnit.SECONDS));

    assertEquals(1, maximumActive.get());
    assertEquals(List.of("request-1", "request-2"), processingOrder);
    awaitState("request-1", AiRequest.State.COMPLETED);
    awaitState("request-2", AiRequest.State.COMPLETED);
  }

  @Test
  public void processesQueuedRequestAfterCoordinatorRecreation() throws Exception {
    coordinator.stop();
    store.admit(message("request-1", "event-1"));
    CountDownLatch processed = new CountDownLatch(1);
    requestExecutor = Executors.newScheduledThreadPool(2);
    leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    coordinator =
        new AiRequestCoordinator(
            store,
            requestExecutor,
            leaseExecutor,
            LEASE_MILLIS,
            RECOVERY_INTERVAL_MILLIS);

    coordinator.start(
        request -> {
          processed.countDown();
          return ProcessingOutcome.COMPLETED;
        });

    assertTrue(processed.await(5, TimeUnit.SECONDS));
    awaitState("request-1", AiRequest.State.COMPLETED);
  }

  @Test
  public void abandonsExpiredOwnerAndProcessesNextRequest() throws Exception {
    coordinator.stop();
    store.admit(message("request-1", "event-1"));
    assertEquals(
        "request-1", store.claimNext(CHANGE_ID, "old-owner", 0L).orElseThrow().requestId());
    store.admit(message("request-2", "event-2"));
    CountDownLatch processed = new CountDownLatch(1);
    CountDownLatch recovered = new CountDownLatch(1);
    AtomicReference<String> recoveredRequestId = new AtomicReference<>();
    requestExecutor = Executors.newScheduledThreadPool(2);
    leaseExecutor = Executors.newSingleThreadScheduledExecutor();
    coordinator =
        new AiRequestCoordinator(
            store,
            requestExecutor,
            leaseExecutor,
            LEASE_MILLIS,
            RECOVERY_INTERVAL_MILLIS);

    coordinator.start(
        request -> {
          processed.countDown();
          return ProcessingOutcome.COMPLETED;
        },
        request -> {
          recoveredRequestId.set(request.requestId());
          recovered.countDown();
        });

    assertTrue(recovered.await(5, TimeUnit.SECONDS));
    assertTrue(processed.await(5, TimeUnit.SECONDS));
    assertEquals("request-1", recoveredRequestId.get());
    assertEquals(AiRequest.State.ABANDONED, store.get("request-1").orElseThrow().state());
    awaitState("request-2", AiRequest.State.COMPLETED);
  }

  @Test
  public void failureReleasesLaneForNextRequest() throws Exception {
    CountDownLatch processed = new CountDownLatch(1);
    coordinator.admit(
        message("request-1", "event-1"),
        request -> {
          throw new IllegalStateException(AiRequest.State.FAILED.name());
        });
    coordinator.admit(
        message("request-2", "event-2"),
        request -> {
          processed.countDown();
          return ProcessingOutcome.COMPLETED;
        });

    assertTrue(processed.await(5, TimeUnit.SECONDS));
    awaitState("request-1", AiRequest.State.FAILED);
    awaitState("request-2", AiRequest.State.COMPLETED);
  }

  @Test
  public void supersededOutcomeReleasesLaneForNextRequest() throws Exception {
    CountDownLatch processed = new CountDownLatch(1);
    coordinator.admit(
        review("review-1", "patch-set-1"),
        request -> ProcessingOutcome.SUPERSEDED);
    coordinator.admit(
        message("request-2", "event-2"),
        request -> {
          processed.countDown();
          return ProcessingOutcome.COMPLETED;
        });

    assertTrue(processed.await(5, TimeUnit.SECONDS));
    awaitState("review-1", AiRequest.State.SUPERSEDED);
    awaitState("request-2", AiRequest.State.COMPLETED);
  }

  @Test
  public void newerPatchSetCancelsRunningReviewAndRejectsIncomingReview() throws Exception {
    CountDownLatch oldReviewStarted = new CountDownLatch(1);
    CountDownLatch finishInFlightQuery = new CountDownLatch(1);
    AtomicBoolean oldReviewStopped = new AtomicBoolean();
    AtomicBoolean newReviewProcessed = new AtomicBoolean();
    AtomicInteger aiQueries = new AtomicInteger();
    coordinator.admit(
        review("review-1", "patch-set-1"),
        request -> {
          AiRequestCancellation cancellation = AiRequestCancellation.current();
          try (AiRequestCancellation.Work ignored = cancellation.beginWork()) {
            aiQueries.incrementAndGet();
            oldReviewStarted.countDown();
            finishInFlightQuery.await();
            cancellation.throwIfSupersessionRequested();
            aiQueries.incrementAndGet();
            return ProcessingOutcome.COMPLETED;
          } finally {
            oldReviewStopped.set(true);
          }
        });
    assertTrue(oldReviewStarted.await(5, TimeUnit.SECONDS));

    AiRequest requested =
        coordinator.requestReviewSupersession(CHANGE_ID, 2).orElseThrow();
    AiRequestStore.Admission incoming =
        coordinator.admit(
            review("review-2", "patch-set-2"),
            request -> {
              newReviewProcessed.set(true);
              return ProcessingOutcome.COMPLETED;
            });
    finishInFlightQuery.countDown();

    assertEquals(AiRequest.State.SUPERSEDE_REQUESTED, requested.state());
    assertEquals(AiRequest.State.REJECTED, incoming.request().state());
    awaitState("review-1", AiRequest.State.SUPERSEDED);
    awaitState("review-2", AiRequest.State.REJECTED);
    assertEquals(1, aiQueries.get());
    assertFalse(newReviewProcessed.get());
    assertTrue(oldReviewStopped.get());
  }

  private AiRequestSubmission review(String requestId, String sourceEventId) {
    return new AiRequestSubmission(
        requestId,
        CHANGE_ID,
        sourceEventId,
        AiRequest.Kind.REVIEW,
        AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED,
        GsonUtils.getGson().toJson(Map.of()));
  }

  private AiRequestSubmission message(String requestId, String sourceEventId) {
    return new AiRequestSubmission(
        requestId,
        CHANGE_ID,
        sourceEventId,
        AiRequest.Kind.MESSAGE,
        AiRequest.AdmissionPolicy.QUEUE,
        GsonUtils.getGson().toJson(Map.of()));
  }

  private static void enter(
      AiRequest request,
      AtomicInteger active,
      AtomicInteger maximumActive,
      List<String> processingOrder) {
    processingOrder.add(request.requestId());
    maximumActive.accumulateAndGet(active.incrementAndGet(), Math::max);
  }

  private static void leave(AtomicInteger active, CountDownLatch completed) {
    active.decrementAndGet();
    completed.countDown();
  }

  private void awaitState(String requestId, AiRequest.State expected) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (store.get(requestId).map(AiRequest::state).orElse(null) == expected) {
        return;
      }
      Thread.yield();
    }
    assertEquals(expected, store.get(requestId).orElseThrow().state());
  }
}
