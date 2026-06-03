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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.git.FileEntry;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class GitFileChunkBuilder {
  private final long maxChunkSize;
  private final List<Map<String, String>> chunks = new ArrayList<>();

  private Map<String, String> currentChunk = new LinkedHashMap<>();
  private long currentChunkSize = 0;

  public GitFileChunkBuilder(Configuration config) {
    maxChunkSize = 1024 * 1024 * (long) config.getAiUploadedChunkSizeMb();
  }

  public void addFiles(List<FileEntry> fileEntries) {
    // Start a new chunk if adding the entire directory would exceed maxChunkSize
    long dirSize = fileEntries.stream().mapToLong(FileEntry::getSize).sum();
    if (currentChunkSize + dirSize > maxChunkSize) {
      startNewChunk();
    }
    for (FileEntry fe : fileEntries) {
      log.debug("ChunkBuilder - Processing file: {}", fe.getPath());
      if (fe.getSize() > maxChunkSize) {
        // Handle large file
        startNewChunk();
        Map<String, String> singleFileChunk = new LinkedHashMap<>();
        updateChunk(singleFileChunk, fe);
        chunks.add(singleFileChunk);
        log.warn("File {} exceeds maxChunkSize, added in its own chunk", fe.getPath());
      } else {
        if (currentChunkSize + fe.getSize() > maxChunkSize) {
          startNewChunk();
        }
        updateChunk(currentChunk, fe);
      }
    }
    startNewChunk();
  }

  public List<Map<String, String>> getChunks() {
    startNewChunk();
    return chunks;
  }

  private void updateChunk(Map<String, String> chunk, FileEntry fe) {
    chunk.put(fe.getPath(), fe.getContent());
    currentChunkSize += fe.getSize();
  }

  private void startNewChunk() {
    if (currentChunk.isEmpty()) {
      return;
    }
    log.debug("Started new chunk");
    chunks.add(currentChunk);
    currentChunk = new LinkedHashMap<>();
    currentChunkSize = 0;
  }
}
