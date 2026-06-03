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
import static com.googlesource.gerrit.plugins.reviewai.utils.FileUtils.matchesExtensionList;

@Slf4j
public class GerritClientPatchSetHelper {
  private static final Pattern DIFF_START_PATTERN = Pattern.compile("(?m)^diff --git ");
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

  public static String filterPatchByEnabledFileExtensions(
      String formattedPatch, List<String> enabledFileExtensions) {
    Matcher diffStartMatcher = DIFF_START_PATTERN.matcher(formattedPatch);
    if (!diffStartMatcher.find()) {
      return formattedPatch;
    }

    List<Integer> diffSectionStarts = new ArrayList<>();
    diffSectionStarts.add(diffStartMatcher.start());
    while (diffStartMatcher.find()) {
      diffSectionStarts.add(diffStartMatcher.start());
    }

    StringBuilder filteredPatch = new StringBuilder();
    filteredPatch.append(formattedPatch, 0, diffSectionStarts.get(0));
    diffSectionStarts.add(formattedPatch.length());
    for (int i = 0; i < diffSectionStarts.size() - 1; i++) {
      String diffSection =
          formattedPatch.substring(diffSectionStarts.get(i), diffSectionStarts.get(i + 1));
      String filename = extractFilenameFromPatchSection(diffSection);
      if (filename != null && matchesExtensionList(filename, enabledFileExtensions)) {
        filteredPatch.append(diffSection);
      }
    }

    String result = filteredPatch.toString();
    log.debug("Patch filtered by enabled file extensions: {}", result);
    return result;
  }

  private static String extractFilenameFromPatchSection(String diffSection) {
    Matcher extractFilenameMatcher = EXTRACT_B_FILENAMES_FROM_PATCH_SET.matcher(diffSection);
    if (!extractFilenameMatcher.find()) {
      return null;
    }
    return extractFilenameMatcher.group(1);
  }
}
