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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetEvent;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.StalePatchSetException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class GerritChange {
  private Event event;
  private String eventType;
  private long eventTimeStamp;
  private PatchSetEvent patchSetEvent;
  private String projectName; // Store as string to avoid linking errors
  private BranchNameKey branchNameKey;
  private Change.Key changeKey;
  private String fullChangeId;
  @Setter private String topic;
  @Setter private Integer changeNumber;
  @Setter private Integer patchSetNumber;
  @Setter private String patchSetRevision;
  // "Boolean" is used instead of "boolean" to have "getIsCommentEvent" instead of "isCommentEvent"
  // as getter method
  // (due to Lombok's magic naming convention)
  @Setter private Boolean isCommentEvent = false;

  /**
   * Constructor accepts projectNameKey as Object to avoid type reference in signature.
   * Works with both older (class) and newer (interface) Project.NameKey implementations.
   */
  public GerritChange(Object projectNameKey, BranchNameKey branchNameKey, Change.Key changeKey) {
    this.projectName = getProjectName(projectNameKey);
    this.branchNameKey = branchNameKey;
    this.changeKey = changeKey;
    buildFullChangeId();
  }

  public GerritChange(Event event) {
    this(
        ((PatchSetEvent) event).getProjectNameKey(),
        ((PatchSetEvent) event).getBranchNameKey(),
        ((PatchSetEvent) event).getChangeKey());
    this.event = event;
    eventType = event.getType();
    eventTimeStamp = event.eventCreatedOn;
    patchSetEvent = (PatchSetEvent) event;
    patchSetRevision = getPatchSetAttribute().map(attribute -> attribute.revision).orElse(null);
  }

  public GerritChange(String fullChangeId) {
    this.fullChangeId = fullChangeId;
  }

  public ChangeApi getChangeApi(Configuration config) throws Exception {
    return config
        .getGerritApi()
        .changes()
        .id(projectName, branchNameKey.shortName(), changeKey.get());
  }

  public RevisionApi getRevisionApi(ChangeApi changeApi) throws Exception {
    return patchSetRevision == null || patchSetRevision.isBlank()
        ? changeApi.current()
        : changeApi.revision(patchSetRevision);
  }

  public void requireCurrentRevision(ChangeApi changeApi) throws Exception {
    if (patchSetRevision == null || patchSetRevision.isBlank()) {
      return;
    }
    String currentRevision = changeApi.get(ListChangesOption.CURRENT_REVISION).currentRevision;
    if (!patchSetRevision.equals(currentRevision)) {
      throw new StalePatchSetException(patchSetRevision, currentRevision);
    }
  }

  public Optional<PatchSetAttribute> getPatchSetAttribute() {
    try {
      return Optional.ofNullable(patchSetEvent.patchSet.get());
    } catch (NullPointerException e) {
      if (patchSetNumber == null) {
        return Optional.empty();
      }
      PatchSetAttribute patchSetAttribute = new PatchSetAttribute();
      patchSetAttribute.number = patchSetNumber;
      return Optional.of(patchSetAttribute);
    }
  }

  public Optional<Integer> getChangeNumber() {
    if (changeNumber != null) {
      return Optional.of(changeNumber);
    }
    try {
      return Optional.ofNullable(patchSetEvent.change.get()).map(change -> change.number);
    } catch (NullPointerException e) {
      return Optional.empty();
    }
  }

  public Optional<String> getTopic() {
    if (topic != null && !topic.isBlank()) {
      return Optional.of(topic);
    }
    try {
      return Optional.ofNullable(patchSetEvent.change.get())
          .map(change -> change.topic)
          .filter(topic -> !topic.isBlank());
    } catch (NullPointerException e) {
      return Optional.empty();
    }
  }

  public String getPatchSetEventKey() {
    String patchSet =
        getPatchSetAttribute().map(attribute -> String.valueOf(attribute.number)).orElse("");
    return String.join(
        ":",
        Objects.toString(projectName, ""),
        branchNameKey == null ? "" : branchNameKey.shortName(),
        changeKey == null ? "" : changeKey.get(),
        patchSet);
  }

  /**
   * Returns the Project.NameKey instance created from the stored string.
   * This method is lazily resolved and works across Gerrit versions because:
   * 1. Project.nameKey() factory method exists in both 3.2.x (class) and 3.13.x (interface)
   * 2. The return type is not resolved until this method is called
   */
  public Project.NameKey getProjectNameKey() {
    if (projectName == null && patchSetEvent != null) {
      projectName = getProjectName(patchSetEvent.getProjectNameKey());
    }
    return Project.nameKey(projectName);
  }

  private void buildFullChangeId() {
    fullChangeId =
        String.join(
            "~",
            URLEncoder.encode(projectName, StandardCharsets.UTF_8),
            branchNameKey.shortName(),
            changeKey.get());
  }

  public static String getProjectName(Object projectNameKey) {
    if (projectNameKey == null) {
      return null;
    }
    try {
      Object name = projectNameKey.getClass().getMethod("get").invoke(projectNameKey);
      if (name != null) {
        return name.toString();
      }
    } catch (ReflectiveOperationException e) {
      log.debug("Falling back to project name key toString.", e);
    }
    return projectNameKey.toString();
  }
}
