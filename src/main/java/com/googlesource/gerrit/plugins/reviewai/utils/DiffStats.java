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

/** Measurements over a unified diff. */
public final class DiffStats {

  private static final String FILE_HEADER = "diff --git ";
  private static final String COMBINED_FILE_HEADER = "diff --cc ";
  private static final String HUNK_HEADER = "@@";
  private static final String NO_NEWLINE_MARKER = "\\";

  private DiffStats() {}

  /**
   * Counts the lines a change actually adds or removes.
   *
   * <p>This is what {@code maxReviewLines} means and what its message promises. Counting the lines
   * of the patch instead makes the limit depend on things the author did not do: every file
   * contributes its {@code diff --git}, {@code index}, {@code ---} and {@code +++} lines and a hunk
   * header before a single changed line, and each hunk carries {@code 2 × patchContextLines}
   * unchanged lines of context on top. A rename that JGit reports as a delete and an add costs two
   * full copies of the file. So a change touching many files drifts arbitrarily far above its real
   * size, and lowering {@code patchContextLines} buys headroom without changing the change.
   *
   * <p>Parsed rather than pattern-matched, because {@code ---} and {@code +++} are file headers
   * <em>and</em> plausible beginnings for a line of content. Only lines inside a hunk are counted —
   * that is, after a {@code @@} header and before the next file — so headers are never counted and
   * a line whose content itself starts with {@code ++} still is.
   *
   * @param patch a unified diff, as Gerrit or JGit produces it, or {@code null}
   * @return the number of added and removed lines, ignoring headers, context and binary or
   *     rename-only entries
   */
  public static int changedLines(String patch) {
    if (patch == null || patch.isEmpty()) {
      return 0;
    }
    int changed = 0;
    boolean insideHunk = false;
    for (String line : patch.split("\n", -1)) {
      if (line.startsWith(FILE_HEADER) || line.startsWith(COMBINED_FILE_HEADER)) {
        insideHunk = false;
        continue;
      }
      if (line.startsWith(HUNK_HEADER)) {
        insideHunk = true;
        continue;
      }
      if (!insideHunk || line.startsWith(NO_NEWLINE_MARKER)) {
        // Before the first hunk of a file, so a header. The backslash marks "\ No newline at end of
        // file", which annotates the previous line rather than being one.
        continue;
      }
      if (line.startsWith("+") || line.startsWith("-")) {
        changed++;
      }
    }
    return changed;
  }
}
