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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.patch;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.code.patch.CodeFinderDiff;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.patch.diff.DiffContent;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class CodeFinder {
  private static final String PUNCTUATION_REGEX = "([()\\[\\]{}<>:;,?&+\\-*/%|=])";
  private static final String BEGINNING_DIFF_REGEX = "(?:^|\n)[+\\-]";
  private static final String ENDING_ELLIPSIS_REGEX = "\\.\\.\\.\\W*$";
  private static final List<String> NEW_SIDE_FIELDS = List.of("b", "ab");
  private static final List<String> OLD_SIDE_FIELDS = List.of("a", "ab");

  private final String NON_PRINTING_REPLACEMENT;
  private final String PUNCTUATION_REPLACEMENT;
  private final String PLACEHOLDER_REGEX;
  private final List<CodeFinderDiff> codeFinderDiffs;

  private int commentedLine;
  private Pattern commentedCodePattern;
  private GerritCodeRange currentCodeRange;
  private GerritCodeRange closestCodeRange;

  public CodeFinder(List<CodeFinderDiff> codeFinderDiffs, String randomPlaceholder) {
    this.codeFinderDiffs = codeFinderDiffs;
    NON_PRINTING_REPLACEMENT = "\\\\E" + randomPlaceholder + "\\\\Q";
    PUNCTUATION_REPLACEMENT = "\\\\E" + randomPlaceholder + "\\\\$1" + randomPlaceholder + "\\\\Q";
    PLACEHOLDER_REGEX = "(?:" + randomPlaceholder + ")+";
    log.debug("Initialized CodeFinder with placeholder patterns.");
  }

  public GerritCodeRange findCommentedCode(AiReplyItem replyItem, int commentedLine) {
    this.commentedLine = commentedLine;
    updateCodePattern(replyItem);
    currentCodeRange = null;
    closestCodeRange = null;
    if (!findCodeLinesIfMapped(buildSideDiff(NEW_SIDE_FIELDS))) {
      findCodeLinesIfMapped(buildSideDiff(OLD_SIDE_FIELDS));
    }
    log.debug("Returning closest code range found.");
    return closestCodeRange;
  }

  private boolean findCodeLinesIfMapped(SearchableDiff searchableDiff) {
    try {
      return findCodeLines(searchableDiff);
    } catch (IllegalArgumentException e) {
      log.warn(
          "Could not retrieve line number from charToLineMap for diff code: {}",
          searchableDiff.diffCode(),
          e);
      return false;
    }
  }

  private SearchableDiff buildSideDiff(List<String> sideFields) {
    StringBuilder diffCode = new StringBuilder();
    TreeMap<Integer, Integer> charToLineMap = new TreeMap<>();
    for (CodeFinderDiff codeFinderDiff : codeFinderDiffs) {
      for (String fieldName : sideFields) {
        String diffItem = getDiffItem(fieldName, codeFinderDiff.getContent());
        if (diffItem == null || diffItem.isEmpty()) {
          continue;
        }
        if (!diffCode.isEmpty()) {
          diffCode.append('\n');
        }
        int offset = diffCode.length();
        for (Integer position : codeFinderDiff.getCharToLineMap().keySet()) {
          if (position <= diffItem.length()) {
            charToLineMap.put(offset + position, codeFinderDiff.getCharToLineMap().get(position));
          }
        }
        diffCode.append(diffItem);
      }
    }
    if (!diffCode.isEmpty()
        && !charToLineMap.isEmpty()
        && !charToLineMap.containsKey(diffCode.length())) {
      charToLineMap.put(diffCode.length(), charToLineMap.lastEntry().getValue());
    }
    return new SearchableDiff(diffCode.toString(), charToLineMap);
  }

  private void updateCodePattern(AiReplyItem replyItem) {
    log.debug("Updating code pattern based on the reply item's code snippet.");
    String commentedCode =
        replyItem
            .getCodeSnippet()
            .replaceAll(BEGINNING_DIFF_REGEX, "")
            .replaceAll(ENDING_ELLIPSIS_REGEX, "")
            .trim();
    String commentedCodeRegex = Pattern.quote(commentedCode);
    // Generalize the regex to capture snippets where existing sequences of non-printing chars have
    // been modified
    // from the original code
    commentedCodeRegex = commentedCodeRegex.replaceAll("\\s+", NON_PRINTING_REPLACEMENT);
    // Generalize the regex to capture snippets where non-printing chars have been removed from
    // around the
    // punctuation marks of the original code
    commentedCodeRegex = commentedCodeRegex.replaceAll(PUNCTUATION_REGEX, PUNCTUATION_REPLACEMENT);
    // Remove redundant empty literal escape sequences that could have resulted from previous
    // substitutions
    commentedCodeRegex = commentedCodeRegex.replaceAll("\\\\Q\\\\E", "");
    // Obtain a functional regex to match code snippets without relying on non-printing chars
    commentedCodeRegex = commentedCodeRegex.replaceAll(PLACEHOLDER_REGEX, "\\\\s*");
    // Remove any detected trailing matching sequence of non-printing chars
    commentedCodeRegex = commentedCodeRegex.replaceAll("\\\\s\\*$", "");
    commentedCodePattern = Pattern.compile(commentedCodeRegex);
    log.debug("Updated commented code pattern: {}", commentedCodePattern);
  }

  private double calcCodeDistance(GerritCodeRange range, int fromLine) {
    return Math.abs((range.endLine + range.startLine) / 2 - fromLine);
  }

  private String getDiffItem(String fieldName, DiffContent diffItem) {
    return switch (fieldName) {
      case "a" -> diffItem.a;
      case "b" -> diffItem.b;
      case "ab" -> diffItem.ab;
      default -> null;
    };
  }

  private int getLineNumber(TreeMap<Integer, Integer> charToLineMapItem, int position) {
    Integer floorPosition = charToLineMapItem.floorKey(position);
    if (floorPosition == null) {
      throw new IllegalArgumentException("Position: " + position);
    }
    return charToLineMapItem.get(floorPosition);
  }

  private int getLineCharacter(String diffCode, int position) {
    // Return the offset relative to the nearest preceding newline character if found, `position`
    // otherwise
    return position - diffCode.substring(0, position).lastIndexOf("\n") - 1;
  }

  private boolean findCodeLines(SearchableDiff searchableDiff) throws IllegalArgumentException {
    String diffCode = searchableDiff.diffCode();
    TreeMap<Integer, Integer> charToLineMapItem = searchableDiff.charToLineMap();
    if (diffCode.isEmpty()) {
      return false;
    }
    log.debug("Current diffCode to match: {}", diffCode);
    Matcher codeMatcher = commentedCodePattern.matcher(diffCode);
    boolean found = false;
    while (codeMatcher.find()) {
      int startPosition = codeMatcher.start();
      int endPosition = codeMatcher.end();
      int startLine = getLineNumber(charToLineMapItem, startPosition);
      int endLine = getLineNumber(charToLineMapItem, endPosition);
      if (startLine > endLine) {
        log.info(
            "Code range discarded: start line ({}) greater than end line ({}).\ncodeMatcher: {}.\n"
                + "diffCode: {}",
            startLine,
            endLine,
            codeMatcher,
            diffCode);
        continue;
      }
      int startCharacter = getLineCharacter(diffCode, startPosition);
      int endCharacter = getLineCharacter(diffCode, endPosition);
      if (startLine == endLine && startCharacter > endCharacter) {
        log.info(
            "Code range discarded: start char ({}) greater than end char ({}) for line {}.\ncodeMatcher:"
                + " {}.\ndiffCode: {}",
            startCharacter,
            endCharacter,
            startLine,
            codeMatcher,
            diffCode);
        continue;
      }
      currentCodeRange =
          GerritCodeRange.builder()
              .startLine(startLine)
              .endLine(endLine)
              .startCharacter(startCharacter)
              .endCharacter(endCharacter)
              .build();
      found = true;
      log.debug("Evaluated current code range: {}", currentCodeRange);
      // If multiple commented code portions are found and currentCommentRange is closer to the line
      // number suggested by AI than closestCommentRange, it becomes the new
      // closestCommentRange
      if (closestCodeRange == null
          || calcCodeDistance(currentCodeRange, commentedLine)
              < calcCodeDistance(closestCodeRange, commentedLine)) {
        closestCodeRange = currentCodeRange.toBuilder().build();
        log.debug("New closest code range set: {}", closestCodeRange);
      }
    }
    return found;
  }

  private record SearchableDiff(String diffCode, TreeMap<Integer, Integer> charToLineMap) {}
}
