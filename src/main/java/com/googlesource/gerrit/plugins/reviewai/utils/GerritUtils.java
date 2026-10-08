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

package com.googlesource.gerrit.plugins.reviewai.utils;

import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_COMMIT_MESSAGE_FILENAME;
import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_PATCH_SET_FILENAME;

import java.util.regex.Pattern;

public final class GerritUtils {
  private static final Pattern COMMIT_MESSAGE_PATH_PATTERN =
      Pattern.compile("(?:/|reviewai-topic-change-[0-9]+/)COMMIT_MSG");

  private GerritUtils() {}

  /** Whether a path names a commit message rather than repository content. */
  public static boolean isCommitMessagePath(String path) {
    return GERRIT_COMMIT_MESSAGE_FILENAME.substring(1).equals(path)
        || isGerritCommitMessagePath(path);
  }

  /** Whether a comment target is Gerrit metadata rather than a file of the revision. */
  public static boolean isGerritNonFilePath(String filename) {
    return GERRIT_PATCH_SET_FILENAME.equals(filename) || isGerritCommitMessagePath(filename);
  }

  private static boolean isGerritCommitMessagePath(String path) {
    return path != null && COMMIT_MESSAGE_PATH_PATTERN.matcher(path).matches();
  }
}
