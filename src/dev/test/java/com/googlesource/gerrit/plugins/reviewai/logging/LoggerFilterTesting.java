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

package com.googlesource.gerrit.plugins.reviewai.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggerFilterTesting extends Filter<ILoggingEvent> {
  private LoggerFilterDecider loggerFilterDecider;
  private Level filterLevel;

  public void setFilterLevel(String levelStr) {
    this.filterLevel =
        Level.toLevel(levelStr, Level.DEBUG); // Default level is DEBUG if parsing fails
  }

  public void setFilterValue(String filterValue) {
    if (loggerFilterDecider == null) {
      log.debug("Initializing LoggerFilter decider");
      loggerFilterDecider = new LoggerFilterDecider(filterValue);
    }
  }

  @Override
  public FilterReply decide(ILoggingEvent event) {
    String loggedClassName = event.getLoggerName();
    String message = event.getMessage();
    Level level = event.getLevel();
    if (level.isGreaterOrEqual(filterLevel)
        || loggerFilterDecider.shouldOverrideLogLevel(loggedClassName, message)) {
      return FilterReply.ACCEPT;
    }
    return FilterReply.DENY;
  }
}
