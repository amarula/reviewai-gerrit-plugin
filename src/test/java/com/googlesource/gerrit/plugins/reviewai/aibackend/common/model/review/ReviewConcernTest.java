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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.List;
import org.junit.Test;

public class ReviewConcernTest {
  @Test
  public void serializesCanonicalConcernFields() {
    ConcernLocation location = new ConcernLocation();
    location.setFilename("src/Example.java");
    location.setLineNumber(42);
    location.setCodeSnippet("run(value);");

    ReviewConcern concern = new ReviewConcern();
    concern.setId("concern-1");
    concern.setStatus(ConcernStatus.FIXED);
    concern.setStatusReason("A guard now rejects null.");
    concern.setMergedConcernIds(List.of("raw-1", "raw-2"));
    concern.setReviewers(
        List.of(
            new ConcernReviewerId(
                ConcernReviewerId.Kind.SPECIALIZED_AGENT, "CORRECTNESS")));
    concern.setLocations(List.of(location));

    ReviewConcern restored = getGson().fromJson(getGson().toJson(concern), ReviewConcern.class);
    restored.normalize();

    assertEquals(concern, restored);
  }

  @Test
  public void normalizeRestoresCollectionAndStatusDefaults() {
    ReviewConcern concern = getGson().fromJson("{}", ReviewConcern.class);
    concern.normalize();

    assertEquals(ConcernStatus.PRESENT, concern.getStatus());
    assertNotNull(concern.getMergedConcernIds());
    assertNotNull(concern.getReviewers());
    assertNotNull(concern.getLocations());
  }
}
