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

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.AiRequestCancellation;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequest;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestStore;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestSubmission;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

/** Runs durable AI requests sequentially within each Change while allowing parallel Changes. */
@Singleton
@Slf4j
public class AiRequestCoordinator {
  private static final int DEFAULT_EXECUTOR_POOL_SIZE = 2;
  private static final long DEFAULT_LEASE_MILLIS = TimeUnit.MINUTES.toMillis(15);
  private static final long DEFAULT_RECOVERY_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(1);

  private final AiRequestStore store;
  private final ScheduledExecutorService requestExecutor;
  private final ScheduledExecutorService leaseExecutor;
  private final long leaseMillis;
  private final long recoveryIntervalMillis;
  private final String ownerId = UUID.randomUUID().toString();
  private final Map<String, RequestProcessor> preparedProcessors = new ConcurrentHashMap<>();
  private final Map<String, ActiveRequest> activeRequests = new ConcurrentHashMap<>();
  private final Set<String> scheduledChanges = ConcurrentHashMap.newKeySet();

  private volatile RequestProcessor persistedProcessor;
  private volatile RecoveryProcessor recoveryProcessor;
  private volatile ScheduledFuture<?> recoveryTask;
  private volatile boolean stopping;

  @Inject
  AiRequestCoordinator(
      AiRequestStore store,
      WorkQueue workQueue,
      @PluginName String pluginName,
      PluginConfigFactory pluginConfigFactory) {
    this(
        store,
        workQueue.createQueue(
            pluginConfigFactory
                .getFromGerritConfig(pluginName)
                .getInt("maximumPoolSize", DEFAULT_EXECUTOR_POOL_SIZE),
            "AI request executor"),
        workQueue.createQueue(1, "AI request lease executor"),
        DEFAULT_LEASE_MILLIS,
        DEFAULT_RECOVERY_INTERVAL_MILLIS);
  }

  @VisibleForTesting
  AiRequestCoordinator(
      AiRequestStore store,
      ScheduledExecutorService requestExecutor,
      ScheduledExecutorService leaseExecutor,
      long leaseMillis,
      long recoveryIntervalMillis) {
    this.store = Objects.requireNonNull(store, "store");
    this.requestExecutor = Objects.requireNonNull(requestExecutor, "requestExecutor");
    this.leaseExecutor = Objects.requireNonNull(leaseExecutor, "leaseExecutor");
    if (leaseMillis <= 0 || recoveryIntervalMillis <= 0) {
      throw new IllegalArgumentException("Lease and recovery intervals must be positive");
    }
    this.leaseMillis = leaseMillis;
    this.recoveryIntervalMillis = recoveryIntervalMillis;
  }

  public synchronized void start(RequestProcessor processor) {
    start(processor, ignored -> {});
  }

  public synchronized void start(
      RequestProcessor processor, RecoveryProcessor recoveredRequestProcessor) {
    persistedProcessor = Objects.requireNonNull(processor, "processor");
    recoveryProcessor =
        Objects.requireNonNull(recoveredRequestProcessor, "recoveredRequestProcessor");
    if (recoveryTask != null) {
      return;
    }
    recover();
    recoveryTask =
        leaseExecutor.scheduleWithFixedDelay(
            this::recoverSafely,
            recoveryIntervalMillis,
            recoveryIntervalMillis,
            TimeUnit.MILLISECONDS);
  }

  public void submitIntake(Runnable intake) {
    requestExecutor.execute(intake);
  }

  public AiRequestStore.Admission admit(
      AiRequestSubmission submission, RequestProcessor preparedProcessor) {
    Objects.requireNonNull(submission, "submission");
    Objects.requireNonNull(preparedProcessor, "preparedProcessor");
    preparedProcessors.put(submission.requestId(), preparedProcessor);
    AiRequestStore.Admission admission;
    try {
      admission = store.admit(submission);
    } catch (RuntimeException e) {
      preparedProcessors.remove(submission.requestId());
      throw e;
    }
    AiRequest request = admission.request();
    if (admission.duplicate() || request.state() != AiRequest.State.QUEUED) {
      preparedProcessors.remove(submission.requestId());
    }
    if (request.state() == AiRequest.State.QUEUED) {
      try {
        schedule(request.changeId());
      } catch (RuntimeException e) {
        preparedProcessors.remove(submission.requestId());
        throw e;
      }
    }
    return admission;
  }

  public Optional<AiRequest> requestReviewSupersession(
      String changeId, long newerPatchSetNumber) {
    String reason = "Superseded by patch set " + newerPatchSetNumber;
    Optional<AiRequest> requested = store.requestSupersession(changeId, reason);
    requested.ifPresent(
        request -> {
          ActiveRequest active = activeRequests.get(changeId);
          if (active != null && active.requestId().equals(request.requestId())) {
            active.cancellation().requestSupersession(reason);
          }
        });
    return requested;
  }

  public synchronized void stop() {
    stopping = true;
    if (recoveryTask != null) {
      recoveryTask.cancel(false);
      recoveryTask = null;
    }
    requestExecutor.shutdownNow();
    leaseExecutor.shutdownNow();
    awaitTermination(requestExecutor, "request");
    awaitTermination(leaseExecutor, "lease");
    preparedProcessors.clear();
    activeRequests.clear();
    scheduledChanges.clear();
  }

  private void schedule(String changeId) {
    if (!scheduledChanges.add(changeId)) {
      return;
    }
    try {
      requestExecutor.execute(() -> drain(changeId));
    } catch (RuntimeException e) {
      scheduledChanges.remove(changeId);
      throw e;
    }
  }

  private void drain(String changeId) {
    try {
      while (!stopping) {
        AiRequest request =
            store
                .claimNext(changeId, ownerId, leaseExpiration())
                .orElse(null);
        if (request == null) {
          return;
        }
        process(request);
      }
    } finally {
      scheduledChanges.remove(changeId);
      if (!stopping && store.hasQueuedRequest(changeId)) {
        schedule(changeId);
      }
    }
  }

  private void process(AiRequest request) {
    RequestProcessor processor = preparedProcessors.remove(request.requestId());
    if (processor == null) {
      processor = persistedProcessor;
    }
    if (processor == null) {
      store.fail(request.requestId(), ownerId, "No persisted AI request processor is available");
      return;
    }
    AiRequestCancellation cancellation =
        new AiRequestCancellation(() -> store.isSupersessionRequested(request.requestId()));
    ActiveRequest active = new ActiveRequest(request.requestId(), cancellation);
    ActiveRequest existing = activeRequests.putIfAbsent(request.changeId(), active);
    if (existing != null) {
      store.fail(request.requestId(), ownerId, "Change already has an active AI request");
      return;
    }
    ScheduledFuture<?> leaseRenewal = null;
    try {
      leaseRenewal = startLeaseRenewal(request);
      if (store.isSupersessionRequested(request.requestId())) {
        cancellation.requestSupersession("AI review superseded by a newer patch set");
      }
      ProcessingOutcome outcome;
      try (AiRequestCancellation.Scope ignored = AiRequestCancellation.activate(cancellation)) {
        outcome = processor.process(request);
      }
      cancellation.awaitWorkCompletion();
      if (cancellation.isSupersessionRequested()) {
        outcome = ProcessingOutcome.SUPERSEDED;
      }
      boolean finished =
          outcome == ProcessingOutcome.SUPERSEDED
              ? store.supersede(request.requestId(), ownerId, "Patch set review superseded")
              : store.complete(request.requestId(), ownerId, null);
      if (!finished) {
        log.warn("AI request {} lost ownership before finishing", request.requestId());
      }
    } catch (Exception e) {
      cancellation.awaitWorkCompletion();
      if (cancellation.isSupersessionRequested()) {
        store.supersede(request.requestId(), ownerId, "Patch set review superseded");
      } else {
        log.error("AI request {} failed", request.requestId(), e);
        store.fail(request.requestId(), ownerId, failureText(e));
        if (e instanceof InterruptedException) {
          Thread.currentThread().interrupt();
        }
      }
    } finally {
      if (leaseRenewal != null) {
        leaseRenewal.cancel(false);
      }
      activeRequests.remove(request.changeId(), active);
    }
  }

  private ScheduledFuture<?> startLeaseRenewal(AiRequest request) {
    long renewalInterval = Math.max(1, leaseMillis / 3);
    return leaseExecutor.scheduleWithFixedDelay(
        () -> renewLease(request),
        renewalInterval,
        renewalInterval,
        TimeUnit.MILLISECONDS);
  }

  private void renewLease(AiRequest request) {
    try {
      if (!store.renewLease(request.requestId(), ownerId, leaseExpiration())) {
        log.warn("Could not renew lease for AI request {}", request.requestId());
      }
    } catch (RuntimeException e) {
      log.error("Failed to renew lease for AI request {}", request.requestId(), e);
    }
  }

  private void recoverSafely() {
    try {
      recover();
    } catch (RuntimeException e) {
      log.error("Failed to recover durable AI requests", e);
    }
  }

  private void recover() {
    var abandoned =
        store.abandonExpiredRequests(System.currentTimeMillis(), "AI request lease expired");
    if (!abandoned.isEmpty()) {
      var interrupted =
          abandoned.stream()
              .filter(request -> request.state() == AiRequest.State.ABANDONED)
              .toList();
      if (!interrupted.isEmpty()) {
        log.warn("Abandoned {} expired AI request(s)", interrupted.size());
        interrupted.forEach(this::notifyRecovery);
      }
    }
    store.listQueuedChangeIds(Integer.MAX_VALUE).forEach(this::schedule);
  }

  private void notifyRecovery(AiRequest request) {
    try {
      recoveryProcessor.recover(request);
    } catch (Exception e) {
      log.error("Failed to report recovery of AI request {}", request.requestId(), e);
    }
  }

  private long leaseExpiration() {
    return System.currentTimeMillis() + leaseMillis;
  }

  private static String failureText(Exception failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
  }

  private static void awaitTermination(
      ScheduledExecutorService executor, String executorName) {
    try {
      if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("AI {} executor did not terminate within timeout", executorName);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while stopping AI {} executor", executorName, e);
    }
  }

  @FunctionalInterface
  public interface RequestProcessor {
    ProcessingOutcome process(AiRequest request) throws Exception;
  }

  public enum ProcessingOutcome {
    COMPLETED,
    SUPERSEDED
  }

  @FunctionalInterface
  public interface RecoveryProcessor {
    void recover(AiRequest request) throws Exception;
  }

  private record ActiveRequest(String requestId, AiRequestCancellation cancellation) {}
}
