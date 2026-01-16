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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.util.List;

@Slf4j
public class CopilotInstructions {
  private static final String COPILOT_INSTRUCTIONS_FILENAME = "copilot-instructions.md";

  private final GitRepoFiles gitRepoFiles = new GitRepoFiles();
  private final GerritChange change;

  public CopilotInstructions(GerritChange change) {
    log.debug("Initializing CopilotInstructions with change: {}", change.getFullChangeId());
    this.change = change;
  }

  public void addCopilotInstructions(List<String> instructions) {
    log.debug("Adding copilot instructions");
    String content = getCopilotInstructions();
    log.debug("Retrieved copilot instructions: {}", content);
    if (content != null && !content.isEmpty()) {
      instructions.add(content);
      log.debug("Added copilot instructions from {}", COPILOT_INSTRUCTIONS_FILENAME);
    }
  }

  private String getCopilotInstructions() {
    log.debug("Retrieving copilot instructions from {}", COPILOT_INSTRUCTIONS_FILENAME);
    try {
      String content = gitRepoFiles.getFileContent(this.change, COPILOT_INSTRUCTIONS_FILENAME);
      log.debug("Retrieved copilot instructions content: {}", content);
      if (content == null) {
        return null;
      }
      String trimmedContent = content.trim();
      return trimmedContent.isEmpty() ? null : trimmedContent;
    } catch (FileNotFoundException e) {
      log.debug("Copilot instructions file not found at repo root");
      return null;
    }
  }
}
