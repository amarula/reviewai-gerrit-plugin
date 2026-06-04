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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PatchSetMerger {
  private static final Pattern DIFF_START_PATTERN = Pattern.compile("(?m)^diff --git ");
  private static final Pattern EXTRACT_B_FILENAME_FROM_PATCH_SECTION =
      Pattern.compile("^diff --git .*? b/(.*)$", Pattern.MULTILINE);
  private static final Pattern HUNK_START_PATTERN = Pattern.compile("(?m)^@@ ");
  private static final Pattern HUNK_HEADER_PATTERN =
      Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");

  private PatchSetMerger() {}

  static String merge(String originalPatchSet, String patchSetFix) {
    PatchSetParts original = PatchSetParts.parse(originalPatchSet);
    PatchSetParts fix = PatchSetParts.parse(patchSetFix);
    if (original.sections().isEmpty()) {
      return patchSetFix.strip() + "\n";
    }
    if (fix.sections().isEmpty()) {
      return originalPatchSet.stripTrailing() + "\n";
    }

    List<FilePatchSection> mergedSections = new ArrayList<>(original.sections());
    Map<String, Integer> sectionIndexes = indexSectionsByFilename(mergedSections);
    for (FilePatchSection fixSection : fix.sections()) {
      Integer sectionIndex = sectionIndexes.get(fixSection.filename());
      if (sectionIndex == null) {
        sectionIndexes.put(fixSection.filename(), mergedSections.size());
        mergedSections.add(fixSection);
        continue;
      }
      mergedSections.set(sectionIndex, mergedSections.get(sectionIndex).merge(fixSection));
    }
    return PatchSetParts.format(original.header(), mergedSections);
  }

  private static Map<String, Integer> indexSectionsByFilename(List<FilePatchSection> sections) {
    Map<String, Integer> sectionIndexes = new LinkedHashMap<>();
    for (int i = 0; i < sections.size(); i++) {
      sectionIndexes.putIfAbsent(sections.get(i).filename(), i);
    }
    return sectionIndexes;
  }

  private record PatchSetParts(String header, List<FilePatchSection> sections) {
    private static PatchSetParts parse(String patchSet) {
      Matcher diffStartMatcher = DIFF_START_PATTERN.matcher(patchSet);
      if (!diffStartMatcher.find()) {
        return new PatchSetParts(patchSet, List.of());
      }

      String header = patchSet.substring(0, diffStartMatcher.start());
      List<Integer> sectionStarts = new ArrayList<>();
      sectionStarts.add(diffStartMatcher.start());
      while (diffStartMatcher.find()) {
        sectionStarts.add(diffStartMatcher.start());
      }

      List<FilePatchSection> sections = new ArrayList<>();
      sectionStarts.add(patchSet.length());
      for (int i = 0; i < sectionStarts.size() - 1; i++) {
        String section = patchSet.substring(sectionStarts.get(i), sectionStarts.get(i + 1));
        FilePatchSection.from(section).ifPresent(sections::add);
      }
      return new PatchSetParts(header, sections);
    }

    private static String format(String header, List<FilePatchSection> sections) {
      StringBuilder result = new StringBuilder();
      if (header != null && !header.isBlank()) {
        result.append(header.stripTrailing()).append("\n\n");
      }
      for (FilePatchSection section : sections) {
        if (!section.content().isBlank()) {
          result.append(section.content().strip()).append("\n");
        }
      }
      return result.toString();
    }
  }

  private record FilePatchSection(String filename, String content) {
    private static java.util.Optional<FilePatchSection> from(String content) {
      Matcher filenameMatcher = EXTRACT_B_FILENAME_FROM_PATCH_SECTION.matcher(content);
      if (!filenameMatcher.find()) {
        return java.util.Optional.empty();
      }
      return java.util.Optional.of(new FilePatchSection(filenameMatcher.group(1), content));
    }

    private FilePatchSection merge(FilePatchSection patchSetFix) {
      String originalHeader = header(content);
      if (originalHeader.isBlank()) {
        return patchSetFix;
      }
      List<PatchHunk> originalHunks = parseHunks(content);
      List<PatchHunk> fixHunks = parseHunks(patchSetFix.content());
      if (originalHunks.isEmpty() || fixHunks.isEmpty()) {
        return patchSetFix;
      }

      List<PatchHunk> mergedHunks = new ArrayList<>(originalHunks);
      for (PatchHunk fixHunk : fixHunks) {
        Optional<Integer> matchingHunkIndex = findMatchingHunk(mergedHunks, fixHunk);
        if (matchingHunkIndex.isEmpty()) {
          mergedHunks.add(fixHunk);
          continue;
        }
        PatchHunk originalHunk = mergedHunks.get(matchingHunkIndex.get());
        PatchHunk mergedHunk = originalHunk.merge(fixHunk);
        if (mergedHunk.isNoOp()) {
          mergedHunks.remove((int) matchingHunkIndex.get());
        } else {
          mergedHunks.set(matchingHunkIndex.get(), mergedHunk);
        }
      }
      if (mergedHunks.isEmpty()) {
        return new FilePatchSection(filename, "");
      }
      String mergedPatchHunks =
          String.join("\n", mergedHunks.stream().map(PatchHunk::toPatch).toList());
      return new FilePatchSection(
          filename, originalHeader.stripTrailing() + "\n" + mergedPatchHunks);
    }

    private static Optional<Integer> findMatchingHunk(List<PatchHunk> hunks, PatchHunk fixHunk) {
      for (int i = 0; i < hunks.size(); i++) {
        if (hunks.get(i).newLines().equals(fixHunk.oldLines())) {
          return Optional.of(i);
        }
      }
      return Optional.empty();
    }

    private static List<PatchHunk> parseHunks(String patchSection) {
      Matcher hunkStartMatcher = HUNK_START_PATTERN.matcher(patchSection);
      List<Integer> hunkStarts = new ArrayList<>();
      while (hunkStartMatcher.find()) {
        hunkStarts.add(hunkStartMatcher.start());
      }
      List<PatchHunk> hunks = new ArrayList<>();
      hunkStarts.add(patchSection.length());
      for (int i = 0; i < hunkStarts.size() - 1; i++) {
        String hunk = patchSection.substring(hunkStarts.get(i), hunkStarts.get(i + 1));
        PatchHunk.from(hunk).ifPresent(hunks::add);
      }
      return hunks;
    }

    private static String header(String patchSection) {
      Matcher hunkStartMatcher = HUNK_START_PATTERN.matcher(patchSection);
      if (!hunkStartMatcher.find()) {
        return patchSection;
      }
      return patchSection.substring(0, hunkStartMatcher.start());
    }

  }

  private record PatchHunk(int oldStart, List<String> oldLines, List<String> newLines) {
    private static Optional<PatchHunk> from(String patchHunk) {
      List<String> lines = patchHunk.stripTrailing().lines().toList();
      if (lines.isEmpty()) {
        return Optional.empty();
      }
      Matcher hunkHeaderMatcher = HUNK_HEADER_PATTERN.matcher(lines.get(0));
      if (!hunkHeaderMatcher.matches()) {
        return Optional.empty();
      }
      List<String> oldLines = new ArrayList<>();
      List<String> newLines = new ArrayList<>();
      for (int i = 1; i < lines.size(); i++) {
        String line = lines.get(i);
        if (line.isEmpty()) {
          oldLines.add("");
          newLines.add("");
          continue;
        }
        char prefix = line.charAt(0);
        String content = line.substring(1);
        if (prefix == ' ' || prefix == '-') {
          oldLines.add(content);
        }
        if (prefix == ' ' || prefix == '+') {
          newLines.add(content);
        }
      }
      return Optional.of(
          new PatchHunk(Integer.parseInt(hunkHeaderMatcher.group(1)), oldLines, newLines));
    }

    private PatchHunk merge(PatchHunk patchSetFix) {
      return new PatchHunk(oldStart, oldLines, patchSetFix.newLines());
    }

    private boolean isNoOp() {
      return oldLines.equals(newLines);
    }

    private String toPatch() {
      List<String> patchLines = buildPatchLines(oldLines, newLines);
      return "@@ -"
          + oldStart
          + ","
          + oldLines.size()
          + " +"
          + oldStart
          + ","
          + newLines.size()
          + " @@\n"
          + String.join("\n", patchLines);
    }

    private static List<String> buildPatchLines(List<String> oldLines, List<String> newLines) {
      int[][] lcs = buildLcsTable(oldLines, newLines);
      List<String> patchLines = new ArrayList<>();
      int oldIndex = 0;
      int newIndex = 0;
      while (oldIndex < oldLines.size() || newIndex < newLines.size()) {
        if (oldIndex < oldLines.size()
            && newIndex < newLines.size()
            && oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
          patchLines.add(" " + oldLines.get(oldIndex));
          oldIndex++;
          newIndex++;
        } else if (newIndex < newLines.size()
            && (oldIndex == oldLines.size()
                || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex])) {
          patchLines.add("+" + newLines.get(newIndex));
          newIndex++;
        } else {
          patchLines.add("-" + oldLines.get(oldIndex));
          oldIndex++;
        }
      }
      return patchLines;
    }

    private static int[][] buildLcsTable(List<String> oldLines, List<String> newLines) {
      int[][] lcs = new int[oldLines.size() + 1][newLines.size() + 1];
      for (int oldIndex = oldLines.size() - 1; oldIndex >= 0; oldIndex--) {
        for (int newIndex = newLines.size() - 1; newIndex >= 0; newIndex--) {
          if (oldLines.get(oldIndex).equals(newLines.get(newIndex))) {
            lcs[oldIndex][newIndex] = lcs[oldIndex + 1][newIndex + 1] + 1;
          } else {
            lcs[oldIndex][newIndex] =
                Math.max(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1]);
          }
        }
      }
      return lcs;
    }
  }
}
