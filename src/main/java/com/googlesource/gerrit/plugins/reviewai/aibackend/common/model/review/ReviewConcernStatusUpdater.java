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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ReviewConcernStatusUpdater {
  private ReviewConcernStatusUpdater() {}

  public static List<ReviewConcern> apply(
      List<ReviewConcern> existingConcerns, List<ReviewConcern> statusUpdates) {
    Map<String, ReviewConcern> existingById = indexById(existingConcerns, "existing");
    Map<String, ReviewConcern> updatesById = indexById(statusUpdates, "updated");
    if (!existingById.keySet().equals(updatesById.keySet())) {
      throw new IllegalArgumentException(
          "Concern status response must contain exactly the existing concern IDs");
    }

    return existingConcerns.stream()
        .map(
            existing -> {
              ReviewConcern update = updatesById.get(existing.getId());
              if (update.getStatus() == null) {
                throw new IllegalArgumentException(
                    "Concern status is required for " + existing.getId());
              }
              if (update.getStatus() == ConcernStatus.SKIPPED) {
                throw new IllegalArgumentException(
                    "SKIPPED can only be assigned by disabled-scope handling");
              }
              ReviewConcern updated = existing.copy();
              updated.setStatus(update.getStatus());
              updated.setStatusReason(update.getStatusReason());
              return updated;
            })
        .toList();
  }

  private static Map<String, ReviewConcern> indexById(
      List<ReviewConcern> concerns, String source) {
    if (concerns == null) {
      throw new IllegalArgumentException(source + " concerns are required");
    }
    Map<String, ReviewConcern> concernsById = new LinkedHashMap<>();
    for (ReviewConcern concern : concerns) {
      if (concern == null || concern.getId() == null || concern.getId().isBlank()) {
        throw new IllegalArgumentException(source + " concern ID is required");
      }
      if (concernsById.putIfAbsent(concern.getId(), concern) != null) {
        throw new IllegalArgumentException(
            "Duplicate " + source + " concern ID: " + concern.getId());
      }
    }
    return concernsById;
  }
}
