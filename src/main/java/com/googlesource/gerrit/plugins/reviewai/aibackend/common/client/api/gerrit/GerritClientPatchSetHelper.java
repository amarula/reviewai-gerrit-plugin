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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.COMMIT_MESSAGE_FILTER_OUT_PREFIXES;
import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_COMMIT_MESSAGE_PREFIX;

@Slf4j
public class GerritClientPatchSetHelper {
  private static final Pattern EXTRACT_B_FILENAMES_FROM_PATCH_SET =
      Pattern.compile("^diff --git .*? b/(.*)$", Pattern.MULTILINE);
  private static final String GERRIT_COMMIT_MESSAGE_PATTERN =
      "^.*?" + GERRIT_COMMIT_MESSAGE_PREFIX + "(?:\\[[^\\]]+\\] )?";

  public static String filterPatchWithCommitMessage(String formattedPatch) {
    // Remove Patch heading up to the Date annotation, so that the commit message is included.
    // Additionally, remove
    // the change type between brackets
    Pattern CONFIG_ID_HEADING_PATTERN =
        Pattern.compile(GERRIT_COMMIT_MESSAGE_PATTERN, Pattern.DOTALL);
    String result =
        CONFIG_ID_HEADING_PATTERN.matcher(formattedPatch).replaceAll(GERRIT_COMMIT_MESSAGE_PREFIX);
    log.debug("Patch filtered with commit message: {}", result);
    return result;
  }

  public static String filterPatchWithoutCommitMessage(GerritChange change, String formattedPatch) {
    // Remove Patch heading up to the Change-Id annotation
    Pattern CONFIG_ID_HEADING_PATTERN =
        Pattern.compile(
            "^.*?"
                + COMMIT_MESSAGE_FILTER_OUT_PREFIXES.get("CHANGE_ID")
                + " "
                + change.getChangeKey().get(),
            Pattern.DOTALL);
    String result = CONFIG_ID_HEADING_PATTERN.matcher(formattedPatch).replaceAll("");
    log.debug("Patch filtered without commit message: {}", result);
    return result;
  }

  public static String filterCommitMessage(String formattedPatch) {
    // Extract commit message from formatted patch
    Pattern CONFIG_ID_HEADING_PATTERN =
        Pattern.compile(
            GERRIT_COMMIT_MESSAGE_PATTERN
                + "(.*?)"
                + COMMIT_MESSAGE_FILTER_OUT_PREFIXES.get("CHANGE_ID"),
            Pattern.DOTALL);
    Matcher commitMessageMatcher = CONFIG_ID_HEADING_PATTERN.matcher(formattedPatch);
    if (commitMessageMatcher.find()) {
      String commitMessage = commitMessageMatcher.group(1).trim();
      log.debug("Commit message extracted: {}", commitMessage);
      return commitMessage;
    } else if (!formattedPatch.contains("diff --git")
        && !formattedPatch.contains(COMMIT_MESSAGE_FILTER_OUT_PREFIXES.get("CHANGE_ID"))) {
      String commitMessage = formattedPatch.trim();
      log.debug("Patch set already contains only the commit message: {}", commitMessage);
      return commitMessage;
    } else {
      log.error("Commit message not found in patch set: {}", formattedPatch);
      throw new RuntimeException("Commit message not found in patch set: " + formattedPatch);
    }
  }

  public static List<String> extractFilesFromPatch(String formattedPatch) {
    Matcher extractFilenameMatcher = EXTRACT_B_FILENAMES_FROM_PATCH_SET.matcher(formattedPatch);
    List<String> files = new ArrayList<>();
    while (extractFilenameMatcher.find()) {
      files.add(extractFilenameMatcher.group(1));
      log.debug("File extracted from patch: {}", extractFilenameMatcher.group(1));
    }
    log.debug("Total files extracted from patch: {}", files.size());
    return files;
  }
}
