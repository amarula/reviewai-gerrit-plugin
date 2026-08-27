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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Change;
import com.google.gerrit.server.events.ChangeAbandonedEvent;
import com.google.gerrit.server.events.ChangeMergedEvent;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerBaseProvider;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GerritListenerTest extends TestBase {
  private static final String INSTANCE_ID = "instance-1";

  @Mock private ConfigCreator configCreator;
  @Mock private EventHandlerExecutor eventHandlerExecutor;
  @Mock private PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider;
  @Mock private LoggingConfigurator loggingConfigurator;
  @Mock private ReviewConcernLifecycleEventHandler reviewConcernLifecycleEventHandler;
  @Mock private Change change;

  private GerritListener listener;

  @Before
  public void setUp() {
    when(change.getProject()).thenReturn(PROJECT_NAME);
    when(change.getDest()).thenReturn(BRANCH_NAME);
    when(change.getKey()).thenReturn(CHANGE_ID);

    listener =
        new GerritListener(
            configCreator,
            eventHandlerExecutor,
            pluginDataHandlerBaseProvider,
            loggingConfigurator,
            reviewConcernLifecycleEventHandler,
            INSTANCE_ID);
  }

  @Test
  public void delegatesMergeToConcernLifecycleHandler() {
    ChangeMergedEvent event = new ChangeMergedEvent(change);
    event.instanceId = INSTANCE_ID;
    when(reviewConcernLifecycleEventHandler.handle(event)).thenReturn(true);

    listener.onEvent(event);

    verify(reviewConcernLifecycleEventHandler).handle(event);
    verify(eventHandlerExecutor, never()).execute(any(), any());
  }

  @Test
  public void delegatesAbandonToConcernLifecycleHandler() {
    ChangeAbandonedEvent event = new ChangeAbandonedEvent(change);
    event.instanceId = INSTANCE_ID;
    when(reviewConcernLifecycleEventHandler.handle(event)).thenReturn(true);

    listener.onEvent(event);

    verify(reviewConcernLifecycleEventHandler).handle(event);
    verify(eventHandlerExecutor, never()).execute(any(), any());
  }

  @Test
  public void ignoresLifecycleEventFromAnotherInstance() {
    ChangeMergedEvent event = new ChangeMergedEvent(change);
    event.instanceId = "other-instance";

    listener.onEvent(event);

    verify(reviewConcernLifecycleEventHandler, never()).handle(any());
  }
}
