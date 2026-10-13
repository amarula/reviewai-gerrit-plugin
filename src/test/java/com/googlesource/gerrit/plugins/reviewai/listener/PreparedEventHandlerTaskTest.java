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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gson.JsonObject;
import com.google.inject.util.Providers;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequest;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiRequestSupersededException;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.StalePatchSetException;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.metrics.ReviewAiMetrics;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewConversationRecorder;
import com.googlesource.gerrit.plugins.reviewai.review.PatchSetReviewer;
import com.googlesource.gerrit.plugins.reviewai.review.ReviewFeedbackLifecycle;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class PreparedEventHandlerTaskTest {
  private final Configuration config = mock(Configuration.class);
  private final GerritApi gerritApi = mock(GerritApi.class);
  private final Changes changes = mock(Changes.class);
  private final ChangeApi changeApi = mock(ChangeApi.class);
  private final RevisionApi revisionApi = mock(RevisionApi.class);
  private final GerritClient gerritClient = mock(GerritClient.class);
  private final IAiClient aiClient = mock(IAiClient.class);
  private final IEventHandlerType handler = mock(IEventHandlerType.class);
  private final ReviewAgentEventRequestStatusUpdater.PendingRequest pendingRequest =
      mock(ReviewAgentEventRequestStatusUpdater.PendingRequest.class);
  private final ReviewAiMetrics metrics = mock(ReviewAiMetrics.class);
  private final ChangeSetData data = new ChangeSetData(1);
  private final GerritChange change =
      new GerritChange("project", BranchNameKey.create("project", "main"), Change.key("I123"));
  private final ReviewResult result = new ReviewResult();
  private JsonObject fixture;
  private GerritClientReview reviewClient;
  private PatchSetReviewer reviewer;
  private Localizer localizer;

  @Before
  public void setUp() throws Exception {
    fixture =
        getGson()
            .fromJson(
                Files.readString(
                    TestResourceLoader.getTestResourcePath()
                        .resolve("__files/review/failureFeedback.json")),
                JsonObject.class);
    when(config.getLocaleDefault()).thenReturn(Locale.ROOT);
    when(config.getGerritApi()).thenReturn(gerritApi);
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id("project", "main", "I123")).thenReturn(changeApi);
    when(changeApi.current()).thenReturn(revisionApi);
    when(revisionApi.review(any())).thenReturn(result);
    when(metrics.startReviewRun(any())).thenReturn(mock(ReviewAiMetrics.MetricTimer.class));
    localizer = new Localizer(config);
    reviewClient = new GerritClientReview(config, localizer);
    reviewer =
        new PatchSetReviewer(
            gerritClient,
            config,
            data,
            Providers.of(reviewClient),
            aiClient,
            localizer,
            mock(PatchSetReviewConversationRecorder.class),
            mock(ReviewConcernPublisher.class),
            mock(ReviewFeedbackLifecycle.class),
            mock(AiReviewApplicabilityChecker.class),
            null);
  }

  @Test
  public void reportsFailureBeforeReviewStartsWithoutRequestingAi() throws Exception {
    failProcessing(new IllegalStateException(failure()));

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    ReviewInput input = publishedInput();
    assertTrue(input.message.endsWith(fixture.get("systemMessage").getAsString()));
    assertEquals(NotifyHandling.NONE, input.notify);
    assertNull(input.labels);
    assertNull(input.comments);
    assertNull(input.reviewers);
    verify(pendingRequest).fail(failure());
    verifyNoInteractions(aiClient);
  }

  @Test
  public void reportsRejectedPublicationAndKeepsSidebarFailure() throws Exception {
    when(revisionApi.review(any())).thenThrow(new AuthException(failure())).thenReturn(result);
    doAnswer(
            invocation -> {
              reviewClient.setReview(change, List.of(finding()), data);
              return null;
            })
        .when(handler)
        .processEvent();

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    ArgumentCaptor<ReviewInput> inputs = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApi, times(2)).review(inputs.capture());
    assertNull(inputs.getAllValues().getLast().comments);
    assertNull(inputs.getAllValues().getLast().labels);
    assertTrue(
        inputs
            .getAllValues()
            .getLast()
            .message
            .endsWith(fixture.get("systemMessage").getAsString()));
    verify(pendingRequest).fail(failure());
  }

  @Test
  public void doesNotReportFailureAfterGerritAcceptedReview() throws Exception {
    doAnswer(
            invocation -> {
              reviewClient.setReview(change, List.of(finding()), data);
              throw new IllegalStateException(failure());
            })
        .when(handler)
        .processEvent();

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    assertTrue(data.isReviewPublished());
    verify(revisionApi).review(any());
    verify(pendingRequest).fail(failure());
  }

  @Test
  public void doesNotReportFailureWhenRefreshingAcceptedReviewFails() throws Exception {
    when(changes.id("project", "main", "I123"))
        .thenReturn(changeApi)
        .thenThrow(new AuthException(failure()));
    doAnswer(
            invocation -> {
              reviewClient.setReview(change, List.of(finding()), data);
              return null;
            })
        .when(handler)
        .processEvent();

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    assertTrue(data.isReviewPublished());
    verify(revisionApi).review(any());
  }

  @Test
  public void reportsGerritDeclaredReviewFailure() throws Exception {
    ReviewResult rejected = new ReviewResult();
    rejected.error = failure();
    when(revisionApi.review(any())).thenReturn(rejected, result);
    doAnswer(
            invocation -> {
              reviewClient.setReview(change, List.of(finding()), data);
              return null;
            })
        .when(handler)
        .processEvent();

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    verify(revisionApi, times(2)).review(any());
    verify(pendingRequest).fail(failure());
  }

  @Test
  public void reportsFailureOnAnotherChangeAfterFirstReviewSucceeded() throws Exception {
    GerritChange secondChange =
        new GerritChange("project", BranchNameKey.create("project", "main"), Change.key("I456"));
    ChangeApi secondApi = mock(ChangeApi.class);
    RevisionApi secondRevision = mock(RevisionApi.class);
    when(changes.id("project", "main", "I456")).thenReturn(secondApi);
    when(secondApi.current()).thenReturn(secondRevision);
    when(secondRevision.review(any())).thenReturn(result);
    doThrow(new IllegalStateException(failure()))
        .when(gerritClient)
        .requireCurrentRevision(secondChange);
    doAnswer(
            invocation -> {
              reviewClient.setReview(change, List.of(finding()), data);
              reviewer.review(secondChange);
              return null;
            })
        .when(handler)
        .processEvent();

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    verify(revisionApi).review(any());
    ArgumentCaptor<ReviewInput> input = ArgumentCaptor.forClass(ReviewInput.class);
    verify(secondRevision).review(input.capture());
    assertTrue(input.getValue().message.endsWith(fixture.get("systemMessage").getAsString()));
    assertNull(input.getValue().comments);
  }

  @Test
  public void doesNotDuplicatePublishedAiConnectionError() throws Exception {
    doAnswer(
            invocation -> {
              data.setReviewSystemMessage(fixture.get("connectionMessage").getAsString());
              reviewClient.setReview(change, List.of(), data);
              throw new IllegalStateException(failure());
            })
        .when(handler)
        .processEvent();

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    verify(revisionApi).review(any());
  }

  @Test
  public void preservesFailureWhenFallbackPublicationAlsoFails() throws Exception {
    failProcessing(new IllegalStateException(failure()));
    when(revisionApi.review(any())).thenThrow(new AuthException(failure()));

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    verify(pendingRequest).fail(failure());
    verify(revisionApi).review(any());
  }

  @Test
  public void doesNotReportNonReviewCommandFailures() throws Exception {
    failProcessing(new IllegalStateException(failure()));

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.MESSAGE).execute());

    verify(revisionApi, never()).review(any());
  }

  @Test
  public void successfulProcessingDoesNotPublishFailureMessage() throws Exception {
    assertEquals(EventHandlerTask.Result.OK, task(AiRequest.Kind.REVIEW).execute());

    verify(revisionApi, never()).review(any());
    verify(pendingRequest).completeReview();
  }

  @Test
  public void doesNotReportSupersessionOrStaleRevisions() throws Exception {
    failProcessing(new AiRequestSupersededException(failure()));
    assertEquals(EventHandlerTask.Result.SUPERSEDED, task(AiRequest.Kind.REVIEW).execute());
    failProcessing(new StalePatchSetException("old-revision", "new-revision"));
    assertEquals(EventHandlerTask.Result.SUPERSEDED, task(AiRequest.Kind.REVIEW).execute());

    verify(revisionApi, never()).review(any());
  }

  @Test
  public void doesNotReportWrappedInterruption() throws Exception {
    failProcessing(new IllegalStateException(new InterruptedException(failure())));

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    verify(revisionApi, never()).review(any());
  }

  @Test
  public void restoresInterruptWithoutPublishingFailure() throws Exception {
    failProcessing(new InterruptedException(failure()));
    try {
      assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());
      assertTrue(Thread.currentThread().isInterrupted());
      verify(revisionApi, never()).review(any());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void doesNotPublishFallbackIfRevisionBecameStale() throws Exception {
    change.setPatchSetRevision("old-revision");
    ChangeInfo info = new ChangeInfo();
    info.currentRevision = "new-revision";
    when(changeApi.get(ListChangesOption.CURRENT_REVISION)).thenReturn(info);
    failProcessing(new IllegalStateException(failure()));

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    verify(revisionApi, never()).review(any());
  }

  @Test
  public void doesNotPublishFallbackIfRequestWasSuperseded() throws Exception {
    doAnswer(
            invocation -> {
              data.getAiRequestCancellation().requestSupersession(failure());
              throw new IllegalStateException(failure());
            })
        .when(handler)
        .processEvent();

    assertEquals(EventHandlerTask.Result.FAILURE, task(AiRequest.Kind.REVIEW).execute());

    verify(revisionApi, never()).review(any());
  }

  private PreparedEventHandlerTask task(AiRequest.Kind kind) {
    return new PreparedEventHandlerTask(
        AiRequestIntakeDecision.persistent(kind, AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED),
        null,
        handler,
        change,
        data,
        reviewer,
        false,
        pendingRequest,
        metrics,
        localizer);
  }

  private void failProcessing(Exception failure) throws Exception {
    doThrow(failure).when(handler).processEvent();
  }

  private String failure() {
    return fixture.get("failure").getAsString();
  }

  private ReviewBatch finding() {
    return new ReviewBatch(fixture.get("finding").getAsString());
  }

  private ReviewInput publishedInput() throws Exception {
    ArgumentCaptor<ReviewInput> input = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApi).review(input.capture());
    return input.getValue();
  }
}
