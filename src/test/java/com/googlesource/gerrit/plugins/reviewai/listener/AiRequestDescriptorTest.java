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
import static org.junit.Assert.assertTrue;

import com.google.common.base.Suppliers;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.ApprovalAttribute;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.events.PatchSetEvent;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequest;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestStore;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestSubmission;
import java.time.Instant;
import org.junit.Test;

public class AiRequestDescriptorTest extends TestBase {
  private static final int CHANGE_NUMBER = 42;
  private static final int ACCOUNT_ID = 1001;
  private static final int PATCH_SET_NUMBER = 3;
  private static final long EVENT_CREATED_ON = 1_725_000_000L;
  private static final String INSTANCE_ID = "gerrit-instance";
  private static final String SOURCE_EVENT_ID = "change-message-id";
  private static final String OWNER_ID = "worker-id";

  @Test
  public void persistedCommentEventCanBeReconstructed() {
    CommentAddedEvent original = commentAddedEvent();
    AiRequestDescriptor descriptor = AiRequestDescriptor.from(original, SOURCE_EVENT_ID);
    AiRequestStore store = new AiRequestStore(getTestReviewAiDb());
    String changeId = new GerritChange(original).getFullChangeId();
    store.admit(
        new AiRequestSubmission(
            "request-id",
            changeId,
            SOURCE_EVENT_ID,
            AiRequest.Kind.MESSAGE,
            AiRequest.AdmissionPolicy.QUEUE,
            descriptor.toJson()));

    AiRequest claimed = store.claimNext(changeId, OWNER_ID, Long.MAX_VALUE).orElseThrow();
    AiRequestDescriptor restoredDescriptor =
        AiRequestDescriptor.fromJson(claimed.payloadJson());
    PatchSetEvent restored = restoredDescriptor.toEvent();

    assertTrue(restored instanceof CommentAddedEvent);
    assertCommonEventData(restored);
    CommentAddedEvent restoredComment = (CommentAddedEvent) restored;
    assertEquals(original.comment, restoredComment.comment);
    assertEquals("reviewer", restoredComment.author.get().username);
    assertEquals(ACCOUNT_ID, restoredComment.author.get().accountId.intValue());
    assertEquals("Verified", restoredComment.approvals.get()[0].type);
    assertEquals("1", restoredComment.approvals.get()[0].value);
    assertEquals(SOURCE_EVENT_ID, restoredDescriptor.sourceEventId());
  }

  @Test
  public void patchSetCreatedEventCanBeReconstructed() {
    PatchSetCreatedEvent original = patchSetCreatedEvent();

    AiRequestDescriptor descriptor =
        AiRequestDescriptor.from(original, "patch-set-created-event");
    PatchSetEvent restored = AiRequestDescriptor.fromJson(descriptor.toJson()).toEvent();

    assertTrue(restored instanceof PatchSetCreatedEvent);
    assertCommonEventData(restored);
    assertEquals("reviewer", ((PatchSetCreatedEvent) restored).uploader.get().username);
  }

  private static CommentAddedEvent commentAddedEvent() {
    CommentAddedEvent event = new CommentAddedEvent(change());
    populateCommonEventData(event);
    event.author = Suppliers.ofInstance(account());
    event.comment = "/" + ClientCommandBase.commandName(CommandSet.REVIEW);
    event.approvals = Suppliers.ofInstance(new ApprovalAttribute[] {approval()});
    return event;
  }

  private static PatchSetCreatedEvent patchSetCreatedEvent() {
    PatchSetCreatedEvent event = new PatchSetCreatedEvent(change());
    populateCommonEventData(event);
    event.uploader = Suppliers.ofInstance(account());
    return event;
  }

  private static void populateCommonEventData(PatchSetEvent event) {
    event.eventCreatedOn = EVENT_CREATED_ON;
    event.instanceId = INSTANCE_ID;
    event.change = Suppliers.ofInstance(changeAttribute());
    event.patchSet = Suppliers.ofInstance(patchSetAttribute());
  }

  private static Change change() {
    Change change =
        new Change(
            CHANGE_ID,
            Change.id(CHANGE_NUMBER),
            Account.id(ACCOUNT_ID),
            BRANCH_NAME,
            Instant.ofEpochSecond(EVENT_CREATED_ON));
    change.setTopic("request-queue");
    return change;
  }

  private static ChangeAttribute changeAttribute() {
    ChangeAttribute attribute = new ChangeAttribute();
    attribute.project = PROJECT_NAME.get();
    attribute.branch = BRANCH_NAME.branch();
    attribute.id = CHANGE_ID.get();
    attribute.number = CHANGE_NUMBER;
    attribute.topic = "request-queue";
    return attribute;
  }

  private static PatchSetAttribute patchSetAttribute() {
    PatchSetAttribute attribute = new PatchSetAttribute();
    attribute.number = PATCH_SET_NUMBER;
    attribute.revision = "deadbeef";
    attribute.ref = "refs/changes/42/42/3";
    attribute.kind = ChangeKind.REWORK;
    attribute.author = account();
    attribute.uploader = account();
    return attribute;
  }

  private static AccountAttribute account() {
    AccountAttribute account = new AccountAttribute();
    account.accountId = ACCOUNT_ID;
    account.name = "Reviewer";
    account.username = "reviewer";
    account.email = "reviewer@example.com";
    return account;
  }

  private static ApprovalAttribute approval() {
    ApprovalAttribute approval = new ApprovalAttribute();
    approval.type = "Verified";
    approval.oldValue = "0";
    approval.value = "1";
    return approval;
  }

  private static void assertCommonEventData(PatchSetEvent event) {
    assertEquals(EVENT_CREATED_ON, event.eventCreatedOn);
    assertEquals(INSTANCE_ID, event.instanceId);
    assertEquals(PROJECT_NAME, event.getProjectNameKey());
    assertEquals(BRANCH_NAME, event.getBranchNameKey());
    assertEquals(CHANGE_ID, event.getChangeKey());
    assertEquals(CHANGE_NUMBER, event.change.get().number);
    assertEquals("request-queue", event.change.get().topic);
    assertEquals(PATCH_SET_NUMBER, event.patchSet.get().number);
    assertEquals("deadbeef", event.patchSet.get().revision);
    assertEquals(ChangeKind.REWORK, event.patchSet.get().kind);
  }
}
