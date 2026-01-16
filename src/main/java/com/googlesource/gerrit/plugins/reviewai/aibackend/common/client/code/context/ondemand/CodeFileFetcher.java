/*
 * Copyright (c) 2025. The Android Open Source Project
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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.ClientBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.git.FileEntry;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class CodeFileFetcher extends ClientBase {
  private final GerritChange change;
  private final GitRepoFiles gitRepoFiles;

  private String basePathRegEx = "";
  private Map<String, String> preloadedFiles = new LinkedHashMap<>();

  public CodeFileFetcher(Configuration config, GerritChange change, GitRepoFiles gitRepoFiles) {
    super(config);
    this.change = change;
    this.gitRepoFiles = gitRepoFiles;
    if (!config.getCodeContextOnDemandBasePath().isEmpty()) {
      basePathRegEx = "^" + config.getCodeContextOnDemandBasePath() + "/";
    }
  }

  public String getFileContent(String filename) throws FileNotFoundException {
    if (preloadedFiles.containsKey(filename)) {
      return preloadedFiles.get(filename);
    }
    if (!basePathRegEx.isEmpty()) {
      filename = filename.replaceAll(basePathRegEx, "");
    }
    return gitRepoFiles.getFileContent(change, filename);
  }

  public Set<String> getFilesInDir(String dirname) {
    preloadedFiles =
        gitRepoFiles.getDirFiles(config, change, dirname).stream()
            .collect(Collectors.toMap(FileEntry::getPath, FileEntry::getContent));
    return preloadedFiles.keySet();
  }
}
