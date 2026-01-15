/*
 * Copyright (c) 2025. The Android Open Source Project
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
import com.google.gerrit.server.data.PatchSetAttribute;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetEvent;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

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
  // "Boolean" is used instead of "boolean" to have "getIsCommentEvent" instead of "isCommentEvent"
  // as getter method
  // (due to Lombok's magic naming convention)
  @Setter private Boolean isCommentEvent = false;

  /**
   * Constructor accepts projectNameKey as Object to avoid type reference in signature.
   * Works with both older (class) and newer (interface) Project.NameKey implementations.
   */
  public GerritChange(Object projectNameKey, BranchNameKey branchNameKey, Change.Key changeKey) {
    this.projectName = extractProjectName(projectNameKey);
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
  }

  public GerritChange(String fullChangeId) {
    this.fullChangeId = fullChangeId;
  }

  public Optional<PatchSetAttribute> getPatchSetAttribute() {
    try {
      return Optional.ofNullable(patchSetEvent.patchSet.get());
    } catch (NullPointerException e) {
      return Optional.empty();
    }
  }

  /**
   * Returns the Project.NameKey instance created from the stored string.
   * This method is lazily resolved and works across Gerrit versions because:
   * 1. Project.nameKey() factory method exists in both 3.2.x (class) and 3.13.x (interface)
   * 2. The return type is not resolved until this method is called
   */
  public Project.NameKey getProjectNameKey() {
    if (projectName == null && patchSetEvent != null) {
      projectName = extractProjectName(patchSetEvent.getProjectNameKey());
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

  private static String extractProjectName(Object projectNameKey) {
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
