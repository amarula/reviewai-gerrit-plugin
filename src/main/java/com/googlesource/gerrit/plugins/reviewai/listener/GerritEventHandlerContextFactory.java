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

import com.google.gerrit.server.events.Event;
import com.google.inject.Injector;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;

/** Creates the event-scoped injector and handler task for one Gerrit event. */
final class GerritEventHandlerContextFactory {
  private final Injector injector;
  private final EventBuildFeatures buildFeatures;

  GerritEventHandlerContextFactory(Injector injector, EventBuildFeatures buildFeatures) {
    this.injector = injector;
    this.buildFeatures = buildFeatures;
  }

  Context create(Configuration config, Event event) {
    Injector eventInjector =
        injector.createChildInjector(
            new GerritEventContextModule(config, event, buildFeatures));
    return new Context(eventInjector, eventInjector.getInstance(EventHandlerTask.class));
  }

  record Context(Injector injector, EventHandlerTask task) {}
}
