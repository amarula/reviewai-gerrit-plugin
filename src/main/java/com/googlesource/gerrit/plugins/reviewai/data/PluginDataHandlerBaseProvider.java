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

package com.googlesource.gerrit.plugins.reviewai.data;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
@Slf4j
public class PluginDataHandlerBaseProvider implements Provider<PluginDataHandler> {
  private static final String PATH_SUFFIX = ".data";
  private static final String PATH_GLOBAL = "global";

  private final Path defaultPluginDataPath;
  private final ReviewAiDb db;
  private final Map<Path, PluginDataHandler> handlers = new ConcurrentHashMap<>();

  @Inject
  public PluginDataHandlerBaseProvider(
      @com.google.gerrit.extensions.annotations.PluginData Path defaultPluginDataPath,
      ReviewAiDb db) {
    this.defaultPluginDataPath = defaultPluginDataPath;
    this.db = db;
    log.debug(
        "PluginDataHandlerBaseProvider initialized with default plugin data path: {}",
        defaultPluginDataPath);
  }

  public PluginDataHandler get(String path) {
    Path dataFile = defaultPluginDataPath.resolve(path + PATH_SUFFIX);
    return handlers.computeIfAbsent(
        dataFile, key -> db != null ? new PluginDataHandler(key, db) : new PluginDataHandler(key));
  }

  @Override
  public PluginDataHandler get() {
    log.debug("Retrieving global PluginDataHandler");
    return get(PATH_GLOBAL);
  }
}
