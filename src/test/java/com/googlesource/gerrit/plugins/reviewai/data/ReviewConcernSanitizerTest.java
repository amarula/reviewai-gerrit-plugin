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

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.client.ChangeStatus;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.server.util.OneOffRequestContext;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ReviewConcernSanitizerTest extends TestBase {
  @Mock private GerritApi gerritApi;
  @Mock private Changes changes;
  @Mock private OneOffRequestContext requestContext;

  private ReviewConcernSanitizer sanitizer;
  private ReviewAiDb db;

  @Before
  public void setUp() {
    db = getTestReviewAiDb();
    sanitizer = new ReviewConcernSanitizer(db, gerritApi, requestContext);
  }

  @Test
  public void removesMergedAbandonedAndMissingLedgersKeepsOpenOnesUsingFullChangeIds()
      throws Exception {
    new ReviewConcernStore(db, "p~main~Imerged").save(new ReviewConcernLedger());
    new ReviewConcernStore(db, "p~main~Iabandoned").save(new ReviewConcernLedger());
    new ReviewConcernStore(db, "p~main~Inew").save(new ReviewConcernLedger());
    new ReviewConcernStore(db, "p~main~Imissing").save(new ReviewConcernLedger());

    ChangeApi mergedApi = changeApiWithStatus(ChangeStatus.MERGED);
    ChangeApi abandonedApi = changeApiWithStatus(ChangeStatus.ABANDONED);
    ChangeApi newApi = changeApiWithStatus(ChangeStatus.NEW);

    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id("p~main~Imerged")).thenReturn(mergedApi);
    when(changes.id("p~main~Iabandoned")).thenReturn(abandonedApi);
    when(changes.id("p~main~Inew")).thenReturn(newApi);
    when(changes.id("p~main~Imissing")).thenThrow(new ResourceNotFoundException("gone"));

    assertEquals(3, sanitizer.sanitize());
    assertEquals(List.of("p~main~Inew"), db.listReviewConcernChangeIds());
  }

  private ChangeApi changeApiWithStatus(ChangeStatus status) throws Exception {
    ChangeApi api = mock(ChangeApi.class);
    ChangeInfo info = new ChangeInfo();
    info.status = status;
    when(api.get()).thenReturn(info);
    return api;
  }
}
