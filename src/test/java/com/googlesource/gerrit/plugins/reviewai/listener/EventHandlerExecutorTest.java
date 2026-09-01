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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Suppliers;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.inject.Injector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandExtension;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequest;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestStore;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestSubmission;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiAdministratorAccess;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class EventHandlerExecutorTest {
  @Test
  public void persistsPreparedPatchSetReviewBeforeExecution() {
    Injector injector = mock(Injector.class);
    Injector childInjector = mock(Injector.class);
    AiRequestCoordinator coordinator = mock(AiRequestCoordinator.class);
    TopicPatchSetReviewCoordinator topicCoordinator =
        mock(TopicPatchSetReviewCoordinator.class);
    EventHandlerTask task = mock(EventHandlerTask.class);
    AtomicReference<AiRequestSubmission> admitted = new AtomicReference<>();
    when(injector.createChildInjector(any(com.google.inject.Module.class)))
        .thenReturn(childInjector);
    when(childInjector.getInstance(EventHandlerTask.class)).thenReturn(task);
    when(task.prepareForIntake(null))
        .thenReturn(
            new EventHandlerTask.Preparation(
                AiRequestIntakeDecision.persistent(
                    AiRequest.Kind.REVIEW,
                    AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED),
                null));
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(coordinator)
        .submitIntake(any());
    when(coordinator.admit(any(), any()))
        .thenAnswer(
            invocation -> {
              AiRequestSubmission submission = invocation.getArgument(0);
              admitted.set(submission);
              return new AiRequestStore.Admission(queued(submission), false);
            });
    EventHandlerExecutor executor =
        new EventHandlerExecutor(
            injector,
            coordinator,
            mock(ConfigCreator.class),
            topicCoordinator,
            mock(AiAdministratorAccess.class),
            mock(ClientCommandExtension.class));
    PatchSetCreatedEvent event = patchSetCreatedEvent();

    executor.execute(mock(Configuration.class), event);

    assertEquals(AiRequest.Kind.REVIEW, admitted.get().kind());
    assertEquals(
        AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED,
        admitted.get().admissionPolicy());
    assertEquals(
        AiRequestDescriptor.EventType.PATCH_SET_CREATED,
        AiRequestDescriptor.fromJson(admitted.get().payloadJson()).eventType());
    verify(topicCoordinator).recordEvent(event);
  }

  @Test
  public void respondsToRejectedUserReviewWithoutExecutingAiRequest() {
    Injector injector = mock(Injector.class);
    Injector childInjector = mock(Injector.class);
    AiRequestCoordinator coordinator = mock(AiRequestCoordinator.class);
    EventHandlerTask task = mock(EventHandlerTask.class);
    when(injector.createChildInjector(any(com.google.inject.Module.class)))
        .thenReturn(childInjector);
    when(childInjector.getInstance(EventHandlerTask.class)).thenReturn(task);
    when(task.prepareForIntake(null))
        .thenReturn(
            new EventHandlerTask.Preparation(
                AiRequestIntakeDecision.persistent(
                    AiRequest.Kind.REVIEW,
                    AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED),
                "change-message-id"));
    when(task.rejectPrepared()).thenReturn(EventHandlerTask.Result.OK);
    doAnswer(
            invocation -> {
              invocation.<Runnable>getArgument(0).run();
              return null;
            })
        .when(coordinator)
        .submitIntake(any());
    when(coordinator.admit(any(), any()))
        .thenAnswer(
            invocation -> {
              AiRequestSubmission submission = invocation.getArgument(0);
              return new AiRequestStore.Admission(
                  request(submission, AiRequest.State.REJECTED), false);
            });
    EventHandlerExecutor executor =
        new EventHandlerExecutor(
            injector,
            coordinator,
            mock(ConfigCreator.class),
            mock(TopicPatchSetReviewCoordinator.class),
            mock(AiAdministratorAccess.class),
            mock(ClientCommandExtension.class));

    executor.execute(mock(Configuration.class), patchSetCreatedEvent());

    verify(task).rejectPrepared();
  }

  @Test
  public void marksExactPendingStatusFailedWhenExpiredRequestIsRecovered()
      throws Exception {
    Injector injector = mock(Injector.class);
    Injector childInjector = mock(Injector.class);
    AiRequestCoordinator coordinator = mock(AiRequestCoordinator.class);
    ConfigCreator configCreator = mock(ConfigCreator.class);
    Configuration config = mock(Configuration.class);
    EventHandlerTask task = mock(EventHandlerTask.class);
    when(injector.createChildInjector(any(com.google.inject.Module.class)))
        .thenReturn(childInjector);
    when(childInjector.getInstance(EventHandlerTask.class)).thenReturn(task);
    when(configCreator.createConfig(any(), any())).thenReturn(config);
    EventHandlerExecutor executor =
        new EventHandlerExecutor(
            injector,
            coordinator,
            configCreator,
            mock(TopicPatchSetReviewCoordinator.class),
            mock(AiAdministratorAccess.class),
            mock(ClientCommandExtension.class));
    String sourceEventId = "change-message-id";
    AiRequestDescriptor descriptor =
        AiRequestDescriptor.from(commentAddedEvent(), sourceEventId);
    AiRequestSubmission submission =
        new AiRequestSubmission(
            "request-id",
            "project~main~I0123456789abcdef",
            sourceEventId,
            AiRequest.Kind.MESSAGE,
            AiRequest.AdmissionPolicy.QUEUE,
            descriptor.toJson());
    ArgumentCaptor<AiRequestCoordinator.RecoveryProcessor> recovery =
        ArgumentCaptor.forClass(AiRequestCoordinator.RecoveryProcessor.class);

    executor.start();
    verify(coordinator)
        .start(any(AiRequestCoordinator.RequestProcessor.class), recovery.capture());
    recovery.getValue().recover(request(submission, AiRequest.State.ABANDONED));

    verify(task).failPendingRequest(sourceEventId);
  }

  private static AiRequest queued(AiRequestSubmission submission) {
    return request(submission, AiRequest.State.QUEUED);
  }

  private static AiRequest request(
      AiRequestSubmission submission, AiRequest.State state) {
    return new AiRequest(
        1,
        submission.requestId(),
        submission.changeId(),
        submission.sourceEventId(),
        submission.kind(),
        submission.admissionPolicy(),
        state,
        submission.payloadJson(),
        null,
        null,
        null,
        1,
        1);
  }

  private static PatchSetCreatedEvent patchSetCreatedEvent() {
    Project.NameKey project = Project.nameKey("project");
    BranchNameKey branch = BranchNameKey.create(project, "refs/heads/main");
    Change change =
        new Change(
            Change.key("I0123456789abcdef"),
            Change.id(42),
            Account.id(1001),
            branch,
            Instant.ofEpochSecond(1_725_000_000L));
    PatchSetCreatedEvent event = new PatchSetCreatedEvent(change);
    event.eventCreatedOn = 1_725_000_000L;
    event.instanceId = "gerrit-instance";
    event.change = Suppliers.ofInstance(changeAttribute(project, branch));
    event.patchSet = Suppliers.ofInstance(patchSetAttribute());
    event.uploader = Suppliers.ofInstance(account());
    return event;
  }

  private static CommentAddedEvent commentAddedEvent() {
    Project.NameKey project = Project.nameKey("project");
    BranchNameKey branch = BranchNameKey.create(project, "refs/heads/main");
    Change change =
        new Change(
            Change.key("I0123456789abcdef"),
            Change.id(42),
            Account.id(1001),
            branch,
            Instant.ofEpochSecond(1_725_000_000L));
    CommentAddedEvent event = new CommentAddedEvent(change);
    event.eventCreatedOn = 1_725_000_000L;
    event.instanceId = "gerrit-instance";
    event.change = Suppliers.ofInstance(changeAttribute(project, branch));
    event.patchSet = Suppliers.ofInstance(patchSetAttribute());
    event.author = Suppliers.ofInstance(account());
    return event;
  }

  private static ChangeAttribute changeAttribute(
      Project.NameKey project, BranchNameKey branch) {
    ChangeAttribute attribute = new ChangeAttribute();
    attribute.project = project.get();
    attribute.branch = branch.branch();
    attribute.id = "I0123456789abcdef";
    attribute.number = 42;
    return attribute;
  }

  private static PatchSetAttribute patchSetAttribute() {
    PatchSetAttribute attribute = new PatchSetAttribute();
    attribute.number = 3;
    attribute.revision = "deadbeef";
    attribute.ref = "refs/changes/42/42/3";
    attribute.kind = ChangeKind.REWORK;
    attribute.author = account();
    attribute.uploader = account();
    return attribute;
  }

  private static AccountAttribute account() {
    AccountAttribute account = new AccountAttribute();
    account.accountId = 1001;
    account.username = "reviewer";
    return account;
  }
}
