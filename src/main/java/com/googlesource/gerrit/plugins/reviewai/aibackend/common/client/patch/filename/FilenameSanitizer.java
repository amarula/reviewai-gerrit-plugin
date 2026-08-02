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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename;

import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class FilenameSanitizer {
  private final List<String> patchSetFiles;

  public FilenameSanitizer(GerritClient gerritClient, GerritChange change) {
    IGerritClientPatchSet gerritClientPatchSet =
        gerritClient.getClientData(change).getGerritClientPatchSet();
    patchSetFiles = gerritClientPatchSet.getPatchSetFiles();
    log.debug("Initialized Patch set files: {}", patchSetFiles);
  }

  public void sanitizeFilename(AiReplyItem replyItem) {
    String filename = replyItem.getFilename();
    log.debug("Sanitizing filename: {}", filename);
    if (filename == null || filename.isEmpty() || patchSetFiles.contains(filename)) {
      return;
    }
    String sanitizedFilename =
        patchSetFiles.stream().filter(s -> s.contains(filename)).findFirst().orElse(null);
    if (sanitizedFilename == null) {
      log.warn("Filename '{}' not sanitized. PatchSet Files: {}", filename, patchSetFiles);
      return;
    }
    log.debug("Filename sanitized: {}", sanitizedFilename);

    replyItem.setFilename(sanitizedFilename);
  }
}
