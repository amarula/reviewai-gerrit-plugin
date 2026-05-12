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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;

import static com.googlesource.gerrit.plugins.reviewai.utils.FileUtils.sanitizeFilename;

@Singleton
@Slf4j
public class PluginDataHandlerProvider extends PluginDataHandlerBaseProvider
    implements Provider<PluginDataHandler> {
  private static final String PATH_ASSISTANTS = ".assistants";

  private final String projectName;
  private final String changeKey;
  private final String assistantsWorkspace;

  @Inject
  public PluginDataHandlerProvider(
      @com.google.gerrit.extensions.annotations.PluginData Path defaultPluginDataPath,
      GerritChange change,
      ReviewAiDb db) {
    super(defaultPluginDataPath, db);
    projectName = sanitizeFilename(change.getProjectName());
    changeKey = change.getChangeKey().toString();
    assistantsWorkspace = projectName + PATH_ASSISTANTS;
    log.debug(
        "PluginDataHandlerProvider initialized for project: {}, change key: {}, workspace: {}",
        projectName,
        changeKey,
        assistantsWorkspace);
  }

  public PluginDataHandlerProvider(Path defaultPluginDataPath, GerritChange change) {
    this(defaultPluginDataPath, change, null);
  }

  public PluginDataHandler getGlobalScope() {
    log.debug("Accessing global scope PluginDataHandler");
    return super.get();
  }

  public PluginDataHandler getProjectScope() {
    log.debug("Accessing PluginDataHandler for project scope: {}", projectName);
    return super.get(projectName);
  }

  public PluginDataHandler getChangeScope() {
    log.debug("Accessing PluginDataHandler for change scope: {}", changeKey);
    return super.get(changeKey);
  }

  public PluginDataHandler getAssistantsWorkspace() {
    log.debug("Accessing PluginDataHandler for assistants workspace: {}", assistantsWorkspace);
    return super.get(assistantsWorkspace);
  }
}
