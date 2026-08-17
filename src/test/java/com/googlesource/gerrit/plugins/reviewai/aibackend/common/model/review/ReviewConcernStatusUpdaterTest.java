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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;

import java.util.List;
import org.junit.Test;

public class ReviewConcernStatusUpdaterTest {
  @Test
  public void appliesOnlyStatusFieldsToExistingConcerns() {
    ReviewConcern existing = concern("concern-1", ConcernStatus.PRESENT, "Original reason");
    existing.setDescription("Original description");
    ReviewConcern update = concern("concern-1", ConcernStatus.FIXED, "Guard added");
    update.setDescription("Untrusted replacement");

    ReviewConcern result =
        ReviewConcernStatusUpdater.apply(List.of(existing), List.of(update)).getFirst();

    assertNotSame(existing, result);
    assertEquals(ConcernStatus.FIXED, result.getStatus());
    assertEquals("Guard added", result.getStatusReason());
    assertEquals("Original description", result.getDescription());
    assertEquals(ConcernStatus.PRESENT, existing.getStatus());
  }

  @Test
  public void rejectsMissingOrUnknownConcernIds() {
    ReviewConcern existing = concern("concern-1", ConcernStatus.PRESENT, "Original reason");
    ReviewConcern unknown = concern("concern-2", ConcernStatus.FIXED, "Not applicable");

    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewConcernStatusUpdater.apply(List.of(existing), List.of(unknown)));
  }

  @Test
  public void rejectsDuplicateConcernIds() {
    ReviewConcern existing = concern("concern-1", ConcernStatus.PRESENT, "Original reason");
    ReviewConcern update = concern("concern-1", ConcernStatus.PRESENT, "Still present");

    assertThrows(
        IllegalArgumentException.class,
        () -> ReviewConcernStatusUpdater.apply(List.of(existing), List.of(update, update)));
  }

  @Test
  public void appliesDismissedStatusAndAllowsLaterReassessment() {
    ReviewConcern present = concern("concern-1", ConcernStatus.PRESENT, "Still actionable");
    ReviewConcern dismissed =
        concern("concern-2", ConcernStatus.DISMISSED, "Accepted by the user");

    List<ReviewConcern> results =
        ReviewConcernStatusUpdater.apply(
            List.of(present, dismissed),
            List.of(
                concern("concern-1", ConcernStatus.DISMISSED, "Risk accepted by the user"),
                concern("concern-2", ConcernStatus.PRESENT, "The dismissal no longer applies")));

    assertEquals(ConcernStatus.DISMISSED, results.get(0).getStatus());
    assertEquals("Risk accepted by the user", results.get(0).getStatusReason());
    assertEquals(ConcernStatus.PRESENT, results.get(1).getStatus());
    assertEquals("The dismissal no longer applies", results.get(1).getStatusReason());
  }

  private static ReviewConcern concern(String id, ConcernStatus status, String statusReason) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId(id);
    concern.setStatus(status);
    concern.setStatusReason(statusReason);
    return concern;
  }
}
