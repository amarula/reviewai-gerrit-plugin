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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getString;
import static com.googlesource.gerrit.plugins.reviewai.utils.StringUtils.cutString;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.ClientBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnDemandCodeContextTools extends ClientBase {
  public static final String TREE = "tree";
  public static final String GET_CONTENT = "get_content";
  public static final String GREP = "grep";
  public static final Set<String> FUNCTION_NAMES = Set.of(TREE, GET_CONTENT, GREP);

  private static final String CONTEXT_NOT_PROVIDED = "CONTEXT NOT PROVIDED";
  private static final int LOG_MAX_CONTENT_SIZE = 256;

  private final GerritChange change;
  private final GitRepoFiles gitRepoFiles;

  public OnDemandCodeContextTools(
      Configuration config, GerritChange change, GitRepoFiles gitRepoFiles) {
    super(config);
    this.change = change;
    this.gitRepoFiles = gitRepoFiles;
  }

  public String execute(String toolName, String arguments) {
    if (!FUNCTION_NAMES.contains(toolName)) {
      log.debug("Ignoring unsupported on-demand code context tool: {}", toolName);
      return "";
    }

    log.debug(
        "On-demand code context request for {}: tool={}, arguments={}",
        getChangeId(),
        toolName,
        arguments);
    String response;
    try {
      JsonObject argumentObject = parseArguments(arguments);
      response =
          switch (toolName) {
            case TREE -> tree(getString(argumentObject, "subdir"));
            case GET_CONTENT -> getContent(getString(argumentObject, "file_path"));
            case GREP -> grep(getString(argumentObject, "string"));
            default -> "";
          };
    } catch (Exception e) {
      log.warn("Error executing on-demand code context tool {}", toolName, e);
      response = CONTEXT_NOT_PROVIDED;
    }
    log.debug(
        "On-demand code context response for {}: tool={}, response={}",
        getChangeId(),
        toolName,
        cutString(response, LOG_MAX_CONTENT_SIZE));
    return response;
  }

  private String tree(String subdir) {
    List<String> paths = gitRepoFiles.getFileTree(config, change, subdir);
    if (paths == null || paths.isEmpty()) {
      return CONTEXT_NOT_PROVIDED;
    }
    return String.join("\n", paths);
  }

  private String getContent(String filePath) throws FileNotFoundException {
    if (filePath == null || filePath.isBlank()) {
      return CONTEXT_NOT_PROVIDED;
    }
    return gitRepoFiles.getFileContent(change, filePath);
  }

  private String grep(String string) {
    if (string == null || string.isEmpty()) {
      return CONTEXT_NOT_PROVIDED;
    }
    List<String> matches = gitRepoFiles.grep(config, change, string);
    if (matches == null || matches.isEmpty()) {
      return CONTEXT_NOT_PROVIDED;
    }
    return String.join("\n", matches);
  }

  private static JsonObject parseArguments(String arguments) {
    if (arguments == null || arguments.isBlank()) {
      return new JsonObject();
    }
    return JsonParser.parseString(arguments).getAsJsonObject();
  }

  private String getChangeId() {
    return change == null ? "<unknown-change>" : change.getFullChangeId();
  }
}
