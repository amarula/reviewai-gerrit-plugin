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

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.ApprovalAttribute;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.events.PatchSetCreatedEvent;
import com.google.gerrit.server.events.PatchSetEvent;
import com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Serializable context required to reconstruct a supported Gerrit event after restart. */
public record AiRequestDescriptor(
    int schemaVersion,
    EventType eventType,
    long eventCreatedOn,
    String instanceId,
    String project,
    String branch,
    String changeKey,
    int changeNumber,
    String topic,
    PatchSetData patchSet,
    AccountData actor,
    String comment,
    List<ApprovalData> approvals,
    String sourceEventId) {
  public static final int CURRENT_SCHEMA_VERSION = 1;

  public AiRequestDescriptor {
    Objects.requireNonNull(eventType, "eventType");
    project = requireNonBlank(project, "project");
    branch = requireNonBlank(branch, "branch");
    changeKey = requireNonBlank(changeKey, "changeKey");
    Objects.requireNonNull(patchSet, "patchSet");
    approvals = approvals == null ? List.of() : List.copyOf(approvals);
    sourceEventId = normalize(sourceEventId);
  }

  public static AiRequestDescriptor from(PatchSetEvent event, String sourceEventId) {
    Objects.requireNonNull(event, "event");
    EventType eventType = EventType.from(event);
    ChangeAttribute change = event.change == null ? null : event.change.get();
    PatchSetAttribute patchSet = event.patchSet == null ? null : event.patchSet.get();
    if (patchSet == null) {
      throw new IllegalArgumentException("patchSet event data is required");
    }
    AccountAttribute actor;
    String comment = null;
    List<ApprovalData> approvals = List.of();
    if (event instanceof CommentAddedEvent commentAddedEvent) {
      actor = commentAddedEvent.author == null ? null : commentAddedEvent.author.get();
      comment = commentAddedEvent.comment;
      ApprovalAttribute[] eventApprovals =
          commentAddedEvent.approvals == null ? null : commentAddedEvent.approvals.get();
      if (eventApprovals != null) {
        approvals = Arrays.stream(eventApprovals).map(ApprovalData::from).toList();
      }
    } else {
      PatchSetCreatedEvent patchSetCreatedEvent = (PatchSetCreatedEvent) event;
      actor = patchSetCreatedEvent.uploader == null ? null : patchSetCreatedEvent.uploader.get();
    }
    return new AiRequestDescriptor(
        CURRENT_SCHEMA_VERSION,
        eventType,
        event.eventCreatedOn,
        event.instanceId,
        event.getProjectNameKey().get(),
        event.getBranchNameKey().branch(),
        event.getChangeKey().get(),
        change == null ? 0 : change.number,
        change == null ? null : change.topic,
        PatchSetData.from(patchSet),
        AccountData.from(actor),
        comment,
        approvals,
        sourceEventId);
  }

  public static AiRequestDescriptor fromJson(String json) {
    AiRequestDescriptor descriptor = GsonUtils.getGson().fromJson(json, AiRequestDescriptor.class);
    if (descriptor == null || descriptor.schemaVersion() != CURRENT_SCHEMA_VERSION) {
      throw new IllegalArgumentException("Unsupported AI request descriptor schema");
    }
    return descriptor;
  }

  public String toJson() {
    return GsonUtils.getGson().toJson(this);
  }

  public PatchSetEvent toEvent() {
    Project.NameKey projectName = Project.nameKey(project);
    BranchNameKey branchName = BranchNameKey.create(projectName, branch);
    int reconstructedChangeNumber = changeNumber > 0 ? changeNumber : 1;
    int ownerId = actor == null || actor.accountId() == null ? 0 : actor.accountId();
    Change change =
        new Change(
            Change.key(changeKey),
            Change.id(reconstructedChangeNumber),
            Account.id(ownerId),
            branchName,
            Instant.ofEpochSecond(eventCreatedOn));
    change.setTopic(topic);
    PatchSetEvent event;
    if (eventType == EventType.COMMENT_ADDED) {
      CommentAddedEvent commentEvent = new CommentAddedEvent(change);
      commentEvent.author = () -> actor == null ? null : actor.toAttribute();
      commentEvent.approvals =
          () -> approvals.stream().map(ApprovalData::toAttribute).toArray(ApprovalAttribute[]::new);
      commentEvent.comment = comment;
      event = commentEvent;
    } else {
      PatchSetCreatedEvent patchSetEvent = new PatchSetCreatedEvent(change);
      patchSetEvent.uploader = () -> actor == null ? null : actor.toAttribute();
      event = patchSetEvent;
    }
    event.eventCreatedOn = eventCreatedOn;
    event.instanceId = instanceId;
    event.patchSet = () -> patchSet.toAttribute();
    event.change = this::toChangeAttribute;
    return event;
  }

  private ChangeAttribute toChangeAttribute() {
    ChangeAttribute attribute = new ChangeAttribute();
    attribute.project = project;
    attribute.branch = branch;
    attribute.id = changeKey;
    attribute.number = changeNumber;
    attribute.topic = topic;
    return attribute;
  }

  public enum EventType {
    PATCH_SET_CREATED,
    COMMENT_ADDED;

    private static EventType from(PatchSetEvent event) {
      if (event instanceof CommentAddedEvent) {
        return COMMENT_ADDED;
      }
      if (event instanceof PatchSetCreatedEvent) {
        return PATCH_SET_CREATED;
      }
      throw new IllegalArgumentException("Unsupported Gerrit event: " + event.getClass().getName());
    }
  }

  public record AccountData(String name, String email, String username, Integer accountId) {
    private static AccountData from(AccountAttribute account) {
      return account == null
          ? null
          : new AccountData(account.name, account.email, account.username, account.accountId);
    }

    private AccountAttribute toAttribute() {
      AccountAttribute attribute = new AccountAttribute();
      attribute.name = name;
      attribute.email = email;
      attribute.username = username;
      attribute.accountId = accountId;
      return attribute;
    }
  }

  public record PatchSetData(
      int number,
      String revision,
      String ref,
      String kind,
      AccountData author,
      AccountData uploader) {
    private static PatchSetData from(PatchSetAttribute patchSet) {
      return new PatchSetData(
          patchSet.number,
          patchSet.revision,
          patchSet.ref,
          patchSet.kind == null ? null : patchSet.kind.name(),
          AccountData.from(patchSet.author),
          AccountData.from(patchSet.uploader));
    }

    PatchSetAttribute toAttribute() {
      PatchSetAttribute attribute = new PatchSetAttribute();
      attribute.number = number;
      attribute.revision = revision;
      attribute.ref = ref;
      attribute.kind = kind == null ? null : ChangeKind.valueOf(kind);
      attribute.author = author == null ? null : author.toAttribute();
      attribute.uploader = uploader == null ? null : uploader.toAttribute();
      return attribute;
    }
  }

  public record ApprovalData(String type, String value, String oldValue) {
    private static ApprovalData from(ApprovalAttribute approval) {
      return new ApprovalData(approval.type, approval.value, approval.oldValue);
    }

    private ApprovalAttribute toAttribute() {
      ApprovalAttribute attribute = new ApprovalAttribute();
      attribute.type = type;
      attribute.value = value;
      attribute.oldValue = oldValue;
      return attribute;
    }
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
