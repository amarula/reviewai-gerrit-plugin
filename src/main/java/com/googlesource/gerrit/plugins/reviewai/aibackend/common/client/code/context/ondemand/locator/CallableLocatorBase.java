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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand.locator;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ondemand.IEntityLocator;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.ClientBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.code.context.ondemand.GetContextItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand.CodeFileFetcher;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.set.ListOrderedSet;

import java.io.FileNotFoundException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.googlesource.gerrit.plugins.reviewai.utils.ModuleUtils.convertDotNotationToPath;
import static com.googlesource.gerrit.plugins.reviewai.utils.ModuleUtils.getSimpleName;
import static com.googlesource.gerrit.plugins.reviewai.utils.FileUtils.getDirName;
import static com.googlesource.gerrit.plugins.reviewai.utils.StringUtils.cutString;

@Slf4j
public abstract class CallableLocatorBase extends ClientBase implements IEntityLocator {
  protected static final String DOT_NOTATION_REGEX = "[\\w.]+";
  private static final int LOG_MAX_CONTENT_SIZE = 256;

  protected final ListOrderedSet<String> importModules = new ListOrderedSet<>();
  protected final CodeFileFetcher codeFileFetcher;

  protected Pattern importPattern;
  protected String[] languageModuleExtensions;
  protected String rootFileDir;

  private int importModulesPointer = 0;
  private Set<String> visitedFiles;

  public CallableLocatorBase(Configuration config, GerritChange change, GitRepoFiles gitRepoFiles) {
    super(config);
    log.debug("Initializing FunctionLocatorBase");
    codeFileFetcher = new CodeFileFetcher(config, change, gitRepoFiles);
  }

  public String findDefinition(GetContextItem getContextItem) {
    log.debug("Finding function definition for {}", getContextItem);
    visitedFiles = new HashSet<>();
    String filename = getContextItem.getFilename();
    String functionName = getSimpleName(getContextItem.getContextRequiredEntity());
    rootFileDir = getDirName(filename);
    log.debug("Root file dir: {}", rootFileDir);
    beforeSearchingFunction();

    return findFunctionInFile(filename, functionName);
  }

  protected abstract String getFunctionRegex(String functionName);

  protected abstract void parseImportStatements(String content);

  protected abstract String findInImportModules(String functionName);

  protected void beforeSearchingFunction() {}

  protected String convertModuleToPath(String module, String languageModuleExtension) {
    return convertDotNotationToPath(module) + languageModuleExtension;
  }

  protected String getFunctionFromModule(String functionName, String module) {
    for (String languageModuleExtension : languageModuleExtensions) {
      String modulePath = convertModuleToPath(module, languageModuleExtension);
      modulePath = modulePath.replaceAll("^(?=/)", rootFileDir);
      log.debug("Module path: {}", modulePath);
      String functionDefinition = findFunctionInFile(modulePath, functionName);
      if (functionDefinition != null) return functionDefinition;
    }
    return null;
  }

  protected Stream<String> getGroupStream(String group) {
    return Arrays.stream(group.split(",")).map(String::trim).filter(entity -> !entity.isEmpty());
  }

  protected String findInModules(String functionName) {
    log.debug("Finding function in modules {} with index {}", importModules, importModulesPointer);
    while (importModulesPointer < importModules.size()) {
      String module = importModules.get(importModulesPointer);
      log.debug("Searching for function `{}` in module: {}", functionName, module);
      String result = getFunctionFromModule(functionName, module);
      if (result != null) return result;
      importModulesPointer++;
    }
    return null;
  }

  private String findImportedFunctionDefinition(String functionName, String content) {
    parseImportStatements(content);

    return findInImportModules(functionName);
  }

  private String findFunctionInFile(String filename, String functionName) {
    log.debug("Finding function {} in file {}", functionName, filename);
    if (visitedFiles.contains(filename)) {
      log.debug("File {} already visited", filename);
      return null;
    }
    visitedFiles.add(filename);

    String content;
    try {
      content = codeFileFetcher.getFileContent(filename);
    } catch (FileNotFoundException e) {
      log.debug("File `{}` not found in the git repository", filename);
      return null;
    }
    log.debug(
        "File content retrieved for file `{}`:\n{}",
        filename,
        cutString(content, LOG_MAX_CONTENT_SIZE));

    // Search the file for the function definition
    Pattern functionPattern = Pattern.compile(getFunctionRegex(functionName), Pattern.MULTILINE);
    Matcher functionMatcher = functionPattern.matcher(content);
    if (functionMatcher.find()) {
      String functionDefinition = functionMatcher.group(0).trim();
      log.debug("Found function definition: {}", functionDefinition);
      return functionDefinition;
    }
    return findImportedFunctionDefinition(functionName, content);
  }
}
