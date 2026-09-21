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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getNonBlankString;
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
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OnDemandCodeContextTools extends ClientBase {
  public static final String TREE = "tree";
  public static final String GET_CONTENT = "get_content";
  public static final String GREP = "grep";
  public static final Set<String> FUNCTION_NAMES = Set.of(TREE, GET_CONTENT, GREP);

  private static final String CONTEXT_NOT_PROVIDED = "CONTEXT NOT PROVIDED";
  private static final String PREEXISTING_CONTEXT_MARKER =
      "NOTE: This file is pre-existing repository context and is NOT part of the current change.\n\n";
  private static final Pattern COMMIT_MESSAGE_PATH_PATTERN =
      Pattern.compile("^(?:reviewai-topic-change-.*)?/?COMMIT_MSG$");
  private static final int LOG_MAX_CONTENT_SIZE = 256;
  static final String SCOPE_CHANGE = "change";
  static final String SCOPE_PROJECT = "project";
  static final int MAX_GREP_MATCHES = 200;

  private static final String SCOPE = "scope";
  // Results that reach beyond the change carry their search space with them, so repository
  // context cannot be mistaken for code introduced by the patch set.
  public static final String PROJECT_SCOPE_HEADER =
      "SCOPE: project (includes files not part of this change)";
  private static final String CHANGE_SCOPE_MISS_FORMAT =
      "NO MATCH IN CHANGED FILES: \"%s\" does not occur in the files changed by this patch set. The rest of the "
          + "repository was not searched; repeat the search with scope=\""
          + SCOPE_PROJECT
          + "\" before concluding that a symbol, declaration or import is missing.";
  private static final String PROJECT_SCOPE_MISS_FORMAT =
      "NO MATCH IN PROJECT: \"%s\" does not occur in the repository at this patch set.";
  private static final String TREE_CHANGE_SCOPE_MISS =
      "NO CHANGED FILES IN SCOPE: the tree is limited to the files changed by this patch set and none match this "
          + "request; repeat with scope=\""
          + SCOPE_PROJECT
          + "\" to inspect the repository tree.";
  private static final String GREP_TRUNCATION_FORMAT =
      "[truncated: showing %d of %d matches; narrow the search string]";

  private final GerritChange change;
  private final GitRepoFiles gitRepoFiles;
  private final TreeOutputCompressor treeOutputCompressor;
  private Set<String> changedFiles;
  private boolean changedFilesResolved;

  public OnDemandCodeContextTools(
      Configuration config, GerritChange change, GitRepoFiles gitRepoFiles) {
    super(config);
    this.change = change;
    this.gitRepoFiles = gitRepoFiles;
    this.treeOutputCompressor = new TreeOutputCompressor();
  }

  private Set<String> changedFiles() {
    if (!changedFilesResolved) {
      changedFilesResolved = true;
      try {
        changedFiles = gitRepoFiles.getPatchSetChangedFiles(change);
      } catch (Exception e) {
        log.warn(
            "Could not resolve changed files for change {}; on-demand tools will not be scoped to the change",
            getChangeId(),
            e);
        changedFiles = null;
      }
    }
    return changedFiles;
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
            case TREE -> tree(getString(argumentObject, "subdir"), isProjectScope(argumentObject));
            case GET_CONTENT -> getContent(getString(argumentObject, "file_path"));
            case GREP -> grep(getString(argumentObject, "string"), isProjectScope(argumentObject));
            default -> "";
          };
    } catch (FileNotFoundException e) {
      log.debug("File not found while executing on-demand code context tool {}", toolName, e);
      response = CONTEXT_NOT_PROVIDED;
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

  private String tree(String subdir, boolean projectScope) {
    List<String> paths = gitRepoFiles.getPatchSetFileTree(config, change, subdir);
    if (paths == null || paths.isEmpty()) {
      return CONTEXT_NOT_PROVIDED;
    }
    Set<String> changed = projectScope ? null : changedFiles();
    if (changed != null) {
      paths = paths.stream().filter(changed::contains).toList();
      if (paths.isEmpty()) {
        return TREE_CHANGE_SCOPE_MISS;
      }
    }
    String formatted = treeOutputCompressor.format(paths, subdir);
    // Without a resolved change, the tree spans the repository even in change scope.
    return projectScope || changed == null ? PROJECT_SCOPE_HEADER + "\n" + formatted : formatted;
  }

  private String getContent(String filePath) throws FileNotFoundException {
    if (filePath == null || filePath.isBlank() || isCommitMessagePath(filePath)) {
      return CONTEXT_NOT_PROVIDED;
    }
    String content = gitRepoFiles.getPatchSetFileContent(change, filePath);
    Set<String> changed = changedFiles();
    if (changed != null && !changed.contains(filePath)) {
      return PREEXISTING_CONTEXT_MARKER + content;
    }
    return content;
  }

  private static boolean isCommitMessagePath(String filePath) {
    return COMMIT_MESSAGE_PATH_PATTERN.matcher(filePath).matches();
  }

  private String grep(String string, boolean projectScope) {
    if (string == null || string.isEmpty()) {
      return CONTEXT_NOT_PROVIDED;
    }
    Set<String> changed = projectScope ? null : changedFiles();
    // Without a resolved change, the search spans the repository even in change scope.
    boolean repositoryWide = projectScope || changed == null;
    List<String> matches = gitRepoFiles.grepPatchSet(config, change, string, changed);
    if (matches == null || matches.isEmpty()) {
      return String.format(
          repositoryWide ? PROJECT_SCOPE_MISS_FORMAT : CHANGE_SCOPE_MISS_FORMAT, string);
    }
    return formatGrepMatches(matches, repositoryWide);
  }

  private static String formatGrepMatches(List<String> matches, boolean repositoryWide) {
    StringBuilder output = new StringBuilder();
    if (repositoryWide) {
      output.append(PROJECT_SCOPE_HEADER).append('\n');
    }
    output.append(
        String.join("\n", matches.subList(0, Math.min(matches.size(), MAX_GREP_MATCHES))));
    if (matches.size() > MAX_GREP_MATCHES) {
      output
          .append('\n')
          .append(String.format(GREP_TRUNCATION_FORMAT, MAX_GREP_MATCHES, matches.size()));
    }
    return output.toString();
  }

  private static boolean isProjectScope(JsonObject arguments) {
    String scope = getNonBlankString(arguments, SCOPE);
    if (SCOPE_PROJECT.equalsIgnoreCase(scope)) {
      return true;
    }
    if (scope != null && !SCOPE_CHANGE.equalsIgnoreCase(scope)) {
      log.debug("Unknown on-demand code context scope `{}`; using `{}`", scope, SCOPE_CHANGE);
    }
    return false;
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
