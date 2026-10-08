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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Whether a block of code the plugin proposed has arrived in a revision.
 *
 * <p>Answers "was this fix applied" without asking a model, so a concern whose fix the author has
 * applied can be closed rather than argued about.
 *
 * <p>It looks at the file's <em>new side</em> in the diff - added lines together with unchanged
 * context - rather than at the added lines alone. Gerrit applies a suggested edit to a range, so
 * any line of the replacement that matches the original stays context in the resulting diff and
 * never appears as an addition; requiring additions would therefore miss precisely the applications
 * that needed no reformatting.
 *
 * <p>Matching ignores indentation and runs of whitespace, because the block is the model's
 * rendering of the code rather than a byte-for-byte copy of it, and the author's editor may have
 * re-indented it. That tolerance errs towards finding the fix, which is the safe direction: the
 * alternative is telling an author who did what they were asked that they did not.
 */
public final class AddedCode {

  private static final String FILE_HEADER = "diff --git ";
  private static final String COMBINED_FILE_HEADER = "diff --cc ";
  private static final String HUNK_HEADER = "@@";
  private static final String NO_NEWLINE_MARKER = "\\";
  private static final Pattern WHITESPACE_RUN = Pattern.compile("[ \t]+");

  private AddedCode() {}

  /**
   * Whether {@code code} now appears in {@code filename}'s part of the revision {@code patch}
   * describes.
   *
   * @param patch a unified diff, or {@code null}
   * @param filename the file to look in, or {@code null} or blank to look in every file the diff
   *     touches
   * @param code the block to look for, as the suggestion presented it
   * @return whether the block is present, contiguously, in the order given
   */
  public static boolean appearsIn(String patch, String filename, String code) {
    List<String> wanted = normalizedLines(code);
    if (wanted.isEmpty()) {
      return false;
    }
    return containsSequence(newSideLines(patch, filename), wanted);
  }

  private static List<String> newSideLines(String patch, String filename) {
    List<String> lines = new ArrayList<>();
    if (patch == null || patch.isEmpty()) {
      return lines;
    }
    boolean insideHunk = false;
    boolean inWantedFile = false;
    int prefixWidth = 1;
    for (String line : patch.split("\n", -1)) {
      if (line.startsWith(FILE_HEADER) || line.startsWith(COMBINED_FILE_HEADER)) {
        insideHunk = false;
        // A combined diff carries one column per parent, so every line has as many prefix
        // characters as it has
        // parents. Reading it as a plain diff leaves a stray marker on the front of every line.
        prefixWidth = line.startsWith(COMBINED_FILE_HEADER) ? 2 : 1;
        inWantedFile = namesFile(line, filename);
        continue;
      }
      if (line.startsWith(HUNK_HEADER)) {
        insideHunk = inWantedFile;
        continue;
      }
      if (!insideHunk || line.startsWith(NO_NEWLINE_MARKER)) {
        // Before the file's first hunk, so a header rather than content; or the
        // "\ No newline at end of file" note, which annotates the previous line.
        continue;
      }
      if (isRemovedFromEveryParent(line, prefixWidth)) {
        // On the old side only, so not part of what the revision now holds. A line removed from one
        // parent but
        // unchanged in another is still in the result, so only an all-removed line is skipped.
        continue;
      }
      String content = line.length() >= prefixWidth ? line.substring(prefixWidth) : "";
      String normalized = normalize(content);
      if (!normalized.isEmpty()) {
        lines.add(normalized);
      }
    }
    return lines;
  }

  private static boolean isRemovedFromEveryParent(String line, int prefixWidth) {
    if (line.length() < prefixWidth) {
      return false;
    }
    for (int column = 0; column < prefixWidth; column++) {
      if (line.charAt(column) != '-') {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a diff header names the file being looked for.
   *
   * <p>A substring test, matching how the filenames the model returns are reconciled elsewhere: the
   * header carries both an {@code a/} and a {@code b/} path and may be quoted, and a patch can use
   * a prefix the model did not see.
   */
  private static boolean namesFile(String header, String filename) {
    return filename == null || filename.isBlank() || header.contains(filename);
  }

  private static List<String> normalizedLines(String code) {
    List<String> lines = new ArrayList<>();
    if (code == null) {
      return lines;
    }
    for (String line : code.split("\n", -1)) {
      String normalized = normalize(line);
      if (!normalized.isEmpty()) {
        // Blank lines are dropped from both sides of the comparison: a diff can render one as a
        // single space or as
        // nothing at all, and insisting on them would reject a block over its spacing alone.
        lines.add(normalized);
      }
    }
    return lines;
  }

  private static String normalize(String line) {
    return WHITESPACE_RUN.matcher(line.strip()).replaceAll(" ");
  }

  private static boolean containsSequence(List<String> lines, List<String> wanted) {
    for (int start = 0; start + wanted.size() <= lines.size(); start++) {
      if (matchesAt(lines, wanted, start)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesAt(List<String> lines, List<String> wanted, int start) {
    for (int offset = 0; offset < wanted.size(); offset++) {
      if (!lines.get(start + offset).equals(wanted.get(offset))) {
        return false;
      }
    }
    return true;
  }
}
