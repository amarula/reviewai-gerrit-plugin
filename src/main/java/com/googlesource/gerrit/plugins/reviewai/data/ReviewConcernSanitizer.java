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

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/** Removes operational review state for changes that are no longer open. */
@Slf4j
@Singleton
public class ReviewConcernSanitizer {
  private final ReviewChangeStateStore reviewChangeStateStore;
  private final GerritApi gerritApi;
  private final OneOffRequestContext requestContext;

  @Inject
  public ReviewConcernSanitizer(
      ReviewChangeStateStore reviewChangeStateStore,
      GerritApi gerritApi,
      OneOffRequestContext requestContext) {
    this.reviewChangeStateStore = reviewChangeStateStore;
    this.gerritApi = gerritApi;
    this.requestContext = requestContext;
  }

  public int sanitize() {
    List<String> changeIds;
    try {
      changeIds = reviewChangeStateStore.listChangeIds();
    } catch (RuntimeException e) {
      log.error("Could not list review change IDs for sanitization", e);
      return 0;
    }
    if (changeIds.isEmpty()) {
      return 0;
    }

    int removed = 0;
    try (ManualRequestContext ignored = requestContext.open()) {
      for (String changeId : changeIds) {
        try {
          if (removeIfClosed(changeId)) {
            removed++;
          }
        } catch (RuntimeException e) {
          log.warn(
              "Could not clear review state for change {} (database unavailable); aborting sanitization",
              changeId,
              e);
          break;
        }
      }
    }
    log.info("Review state sanitization removed {} of {} Changes", removed, changeIds.size());
    return removed;
  }

  private boolean removeIfClosed(String changeId) {
    int separator = changeId.lastIndexOf('~');
    if (separator <= 0 || separator == changeId.length() - 1) {
      log.warn("Skipping review state with malformed change id: {}", changeId);
      return false;
    }
    boolean shouldClear;
    try {
      ChangeInfo info = gerritApi.changes().id(changeId).get();
      shouldClear = info.status == ChangeStatus.MERGED || info.status == ChangeStatus.ABANDONED;
    } catch (ResourceNotFoundException e) {
      shouldClear = true;
    } catch (Exception e) {
      log.warn(
          "Could not resolve status of change {}; keeping its review state", changeId, e);
      return false;
    }

    if (!shouldClear) {
      return false;
    }

    reviewChangeStateStore.clear(changeId);
    log.info("Removed review state for change {}", changeId);
    return true;
  }
}
