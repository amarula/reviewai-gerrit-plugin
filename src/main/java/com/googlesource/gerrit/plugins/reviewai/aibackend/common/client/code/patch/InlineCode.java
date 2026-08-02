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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.patch;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.diff.FileDiffProcessed;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithNewLine;

@Slf4j
public class InlineCode {
  private final CodeFinder codeFinder;
  private final List<String> newContent;
  private GerritCodeRange range;

  public InlineCode(FileDiffProcessed fileDiffProcessed) {
    codeFinder =
        new CodeFinder(
            fileDiffProcessed.getCodeFinderDiffs(), fileDiffProcessed.getRandomPlaceholder());
    newContent = fileDiffProcessed.getNewContent();
    log.debug("InlineCode initialized with file diff processed content.");
  }

  public String getInlineCode(GerritComment commentProperty) {
    log.debug("Retrieving inline code for comment property.");
    if (commentProperty.getRange() != null) {
      List<String> codeByRange = new ArrayList<>();
      range = commentProperty.getRange();
      for (int line_num = range.startLine; line_num <= range.endLine; line_num++) {
        codeByRange.add(getLineSlice(line_num));
      }
      log.debug("Extracted code by range: {}", codeByRange);
      return joinWithNewLine(codeByRange);
    } else {
      return getLineFromLineNumber(commentProperty.getLine());
    }
  }

  public Optional<GerritCodeRange> findCommentRange(AiReplyItem replyItem) {
    log.debug("Finding comment range for AI reply.");
    int commentedLine;
    try {
      commentedLine = replyItem.getLineNumber();
    } catch (NumberFormatException ex) {
      // If the line number is not passed, a line in the middle of the code is used as best guess
      commentedLine = newContent.size() / 2;
      log.debug(
          "Using middle line as best guess for commented line due to exception: {}",
          ex.getMessage());
    }

    return Optional.ofNullable(codeFinder.findCommentedCode(replyItem, commentedLine));
  }

  private String getLineSlice(int line_num) {
    String line = getLineFromLineNumber(line_num);
    if (line == null) {
      throw new RuntimeException("Error retrieving line number from content");
    }
    if (line_num == range.endLine && range.endCharacter < line.length()) {
      line = line.substring(0, range.endCharacter);
    }
    if (line_num == range.startLine && range.startCharacter < line.length()) {
      line = line.substring(range.startCharacter);
    }
    return line;
  }

  private String getLineFromLineNumber(int line_num) {
    String line = null;
    try {
      line = newContent.get(line_num);
    } catch (IndexOutOfBoundsException e) {
      // If the line number returned by AI exceeds the actual number of lines, return the last line
      int lastLine = newContent.size() - 1;
      if (line_num > lastLine) {
        line = newContent.get(lastLine);
        log.info("Returning the last line due to index out of bounds: {} > {}", line_num, lastLine);
      } else {
        log.warn("Could not extract line #{} from the code", line_num);
      }
    }
    return line;
  }
}
