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

import lombok.extern.slf4j.Slf4j;
import org.apache.log4j.Level;
import org.apache.log4j.spi.Filter;
import org.apache.log4j.spi.LoggingEvent;

import java.util.List;

@SuppressWarnings("UnstableApiUsage")
@Slf4j
public class LoggerFilterDeployed extends Filter {
  private final LoggerFilterDecider loggerFilterDecider;
  private final Level thresholdLevel;

  public LoggerFilterDeployed(List<String> filter, Level thresholdLevel) {
    loggerFilterDecider = new LoggerFilterDecider(filter);
    this.thresholdLevel = thresholdLevel;
    log.debug("LoggerFilters Level: {}", thresholdLevel);
  }

  @Override
  public int decide(LoggingEvent event) {
    String loggedClassName = event.getLoggerName();
    String message = event.getMessage().toString();
    Level level = event.getLevel();

    if (level.isGreaterOrEqual(thresholdLevel)
        || loggerFilterDecider.shouldOverrideLogLevel(loggedClassName, message)) {
      return Filter.ACCEPT;
    } else {
      return Filter.DENY;
    }
  }
}
