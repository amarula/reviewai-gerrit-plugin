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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Suppliers;
import com.google.gerrit.entities.Change;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.events.ChangeAbandonedEvent;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.google.gerrit.server.events.Event;
import com.google.gerrit.server.events.PatchSetEvent;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritChangeRef;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequestStore;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewChangeStateStore;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ClosedChangeLifecycleEventHandlerTest extends TestBase {
  private static final GerritChangeRef CHANGE_REF = new GerritChangeRef("gerrit-instance", 42);

  @Mock private ReviewChangeStateStore reviewChangeStateStore;
  @Mock private AiRequestCoordinator aiRequestCoordinator;
  @Mock private AiRequestStore aiRequestStore;
  @Mock private Change change;

  private ClosedChangeLifecycleEventHandler handler;

  @Before
  public void setUp() {
    when(change.getProject()).thenReturn(PROJECT_NAME);
    when(change.getDest()).thenReturn(BRANCH_NAME);
    when(change.getKey()).thenReturn(CHANGE_ID);
    doAnswer(
            invocation -> {
              ((Runnable) invocation.getArgument(1)).run();
              return null;
            })
        .when(aiRequestCoordinator)
        .runWhenChangeIdle(any(), any());
    handler =
        new ClosedChangeLifecycleEventHandler(
            reviewChangeStateStore, aiRequestCoordinator, aiRequestStore);
  }

  @Test
  public void clearsConcernLedgerOnMerge() {
    ChangeMergedEvent event = new ChangeMergedEvent(change);
    populateChangeRef(event);

    assertTrue(handler.handle(event));

    assertClearedForCurrentChange();
    verify(aiRequestCoordinator).cancelRunningReview(CHANGE_REF, "Change merged");
    verify(aiRequestCoordinator).runWhenChangeIdle(any(), any());
    verify(aiRequestStore).deleteByChange(CHANGE_REF);
  }

  @Test
  public void clearsConcernLedgerOnAbandon() {
    ChangeAbandonedEvent event = new ChangeAbandonedEvent(change);
    populateChangeRef(event);

    assertTrue(handler.handle(event));

    assertClearedForCurrentChange();
    verify(aiRequestCoordinator).cancelRunningReview(CHANGE_REF, "Change abandoned");
    verify(aiRequestCoordinator).runWhenChangeIdle(any(), any());
    verify(aiRequestStore).deleteByChange(CHANGE_REF);
  }

  @Test
  public void doesNotHandleOtherEvents() {
    assertFalse(handler.handle(mock(Event.class)));

    verify(reviewChangeStateStore, never()).clear(any());
    verify(aiRequestCoordinator, never()).cancelRunningReview(any(), any());
    verify(aiRequestCoordinator, never()).runWhenChangeIdle(any(), any());
    verify(aiRequestStore, never()).deleteByChange(any());
  }

  private void assertClearedForCurrentChange() {
    ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
    verify(reviewChangeStateStore).clear(captor.capture());
    assertEquals("myProject~myBranchName~myChangeId", captor.getValue());
  }

  private static void populateChangeRef(PatchSetEvent event) {
    ChangeAttribute attribute = new ChangeAttribute();
    attribute.number = CHANGE_REF.changeNumber();
    event.instanceId = CHANGE_REF.instanceId();
    event.change = Suppliers.ofInstance(attribute);
  }
}
