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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.code.patch.CodeFinderDiff;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.patch.diff.DiffContent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeMap;
import org.junit.Test;

public class CodeFinderTest {
  private static final Path TEST_RESOURCES_PATH = Path.of("src/test/resources");
  private static final Path BLE_TEST_CHUNK =
      TEST_RESOURCES_PATH.resolve("__files/codefinder/BleTest.kt");
  private static final String ORIGINAL_MANAGER_LINE =
      "        bleManager = SampleBleManager(bleAdapter)";
  private static final String UPDATED_LISTENER_LINE =
      "        bleManager.registerBleConnectivityListener(this)";
  private static final String ORIGINAL_LISTENER_LINE =
      UPDATED_LISTENER_LINE.replace("this)", "this, appContext)");

  @Test
  public void findCommentedCodeMatchesNewSideSnippetSplitAcrossChangedAndCommonDiffs()
      throws IOException {
    List<String> lines = readResourceLines(BLE_TEST_CHUNK);
    int managerLineNumber = lines.indexOf(ORIGINAL_MANAGER_LINE) + 1;
    List<String> snippetLines =
        lines.subList(managerLineNumber - 1, managerLineNumber + 2);
    CodeFinder codeFinder = createCodeFinder(lines);
    AiReplyItem replyItem =
        AiReplyItem.builder()
            .lineNumber(managerLineNumber)
            .codeSnippet(String.join("\n", snippetLines))
            .build();

    GerritCodeRange range = codeFinder.findCommentedCode(replyItem, managerLineNumber);

    assertNotNull(range);
    assertEquals(managerLineNumber, range.startLine);
    assertEquals(managerLineNumber + 2, range.endLine);
    assertEquals(ORIGINAL_MANAGER_LINE.indexOf("bleManager"), range.startCharacter);
    assertEquals(snippetLines.get(2).length(), range.endCharacter);
  }

  @Test
  public void findCommentedCodeFallsBackToOldSideDiffs() throws IOException {
    List<String> lines = readResourceLines(BLE_TEST_CHUNK);
    int managerLineNumber = lines.indexOf(ORIGINAL_MANAGER_LINE) + 1;
    List<String> snippetLines =
        List.of(
            lines.get(managerLineNumber - 1),
            ORIGINAL_LISTENER_LINE,
            lines.get(managerLineNumber + 1));
    CodeFinder codeFinder = createCodeFinder(lines);
    AiReplyItem replyItem =
        AiReplyItem.builder()
            .lineNumber(managerLineNumber)
            .codeSnippet(String.join("\n", snippetLines))
            .build();

    GerritCodeRange range = codeFinder.findCommentedCode(replyItem, managerLineNumber);

    assertNotNull(range);
    assertEquals(managerLineNumber, range.startLine);
    assertEquals(managerLineNumber + 2, range.endLine);
    assertEquals(ORIGINAL_MANAGER_LINE.indexOf("bleManager"), range.startCharacter);
    assertEquals(snippetLines.get(2).length(), range.endCharacter);
  }

  private static List<String> readResourceLines(Path resourcePath) throws IOException {
    return Files.readAllLines(resourcePath);
  }

  private static CodeFinder createCodeFinder(List<String> lines) {
    int listenerLineNumber = lines.indexOf(UPDATED_LISTENER_LINE) + 1;
    return new CodeFinder(
        List.of(
            codeFinderDiff(commonContent(lines.subList(0, listenerLineNumber - 1)), 1),
            changedCodeFinderDiff(
                ORIGINAL_LISTENER_LINE, UPDATED_LISTENER_LINE, listenerLineNumber),
            codeFinderDiff(
                commonContent(lines.subList(listenerLineNumber, lines.size())),
                listenerLineNumber + 1)),
        "#placeholder");
  }

  private static DiffContent commonContent(List<String> lines) {
    DiffContent diffContent = new DiffContent();
    diffContent.ab = String.join("\n", lines);
    return diffContent;
  }

  private static CodeFinderDiff changedCodeFinderDiff(
      String originalLine, String updatedLine, int lineNumber) {
    DiffContent diffContent = new DiffContent();
    diffContent.a = originalLine;
    diffContent.b = updatedLine;
    TreeMap<Integer, Integer> charToLineMap = new TreeMap<>();
    charToLineMap.put(0, lineNumber);
    charToLineMap.put(updatedLine.length() + 1, lineNumber + 1);
    return new CodeFinderDiff(diffContent, charToLineMap);
  }

  private static CodeFinderDiff codeFinderDiff(DiffContent diffContent, int startingLine) {
    return new CodeFinderDiff(diffContent, charToLineMap(diffContent.ab, startingLine));
  }

  private static TreeMap<Integer, Integer> charToLineMap(String diffCode, int startingLine) {
    TreeMap<Integer, Integer> charToLineMap = new TreeMap<>();
    int lineNumber = startingLine;
    int charPosition = 0;
    charToLineMap.put(charPosition, lineNumber);
    for (int i = 0; i < diffCode.length(); i++) {
      if (diffCode.charAt(i) == '\n') {
        lineNumber++;
        charPosition = i + 1;
        charToLineMap.put(charPosition, lineNumber);
      }
    }
    charToLineMap.put(diffCode.length() + 1, lineNumber + 1);
    return charToLineMap;
  }
}
