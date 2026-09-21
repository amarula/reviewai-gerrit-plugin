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

package com.googlesource.gerrit.plugins.reviewai.logging;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerBaseProvider;
import lombok.extern.slf4j.Slf4j;
import org.apache.log4j.*;

import java.util.List;

@Slf4j
@SuppressWarnings("UnstableApiUsage")
public class LoggingConfigurationDeployed {
  private static final String ORIGINAL_LOG_LEVEL = "originalLogLevel";

  public static void configure(
      Configuration config, PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider) {
    Logger logger = Logger.getRootLogger();
    Appender appender = logger.getAppender("error_log");
    PluginDataHandler globalDataHandler = pluginDataHandlerBaseProvider.get();
    String originalLogLevelStr = globalDataHandler.getValue(ORIGINAL_LOG_LEVEL);
    List<String> selectiveLogLevelOverride = config.getSelectiveLogLevelOverride();
    log.debug("Logger configured for selective override: {} - Appender: {}", logger, appender);
    if (selectiveLogLevelOverride.isEmpty()) {
      if (originalLogLevelStr != null) {
        logger.setLevel(Level.toLevel(originalLogLevelStr));
        log.debug("Log Level restored to {}", originalLogLevelStr);
      }
      return;
    }

    Level currentLevel = logger.getLevel();
    Level originalLogLevel;
    if (originalLogLevelStr == null) {
      if (currentLevel.isGreaterOrEqual(Level.INFO)) {
        globalDataHandler.setValue(ORIGINAL_LOG_LEVEL, currentLevel.toString());
        log.info("Recorded current level: {}", currentLevel);
        originalLogLevel = currentLevel;
      } else {
        originalLogLevel = Level.INFO;
      }
    } else {
      originalLogLevel = Level.toLevel(originalLogLevelStr);
    }
    appender.clearFilters();
    LoggerFilterDeployed filter =
        new LoggerFilterDeployed(selectiveLogLevelOverride, originalLogLevel);
    appender.addFilter(filter);
    log.debug("Log Filters added ({}). Current Level: {}", selectiveLogLevelOverride, currentLevel);
    logger.setLevel(Level.DEBUG);
  }
}
