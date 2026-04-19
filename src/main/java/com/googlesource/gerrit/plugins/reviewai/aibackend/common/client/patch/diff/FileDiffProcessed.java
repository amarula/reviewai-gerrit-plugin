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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.diff;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPatchSetFileDiff;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.code.patch.CodeFinderDiff;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.patch.diff.DiffContent;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithNewLine;

@Slf4j
public class FileDiffProcessed {
  private static final int MIN_RANDOM_PLACEHOLDER_VARIABLE_LENGTH = 1;

  private final Configuration config;
  private final boolean isCommitMessage;
  @Getter private List<CodeFinderDiff> codeFinderDiffs;
  @Getter private List<String> newContent;
  @Getter private List<DiffContent> reviewDiffContent;
  @Getter private String randomPlaceholder;
  private int lineNum;
  private DiffContent diffContentItem;
  private DiffContent reviewDiffContentItem;
  private TreeMap<Integer, Integer> charToLineMapItem;

  public FileDiffProcessed(
      Configuration config,
      boolean isCommitMessage,
      GerritPatchSetFileDiff gerritPatchSetFileDiff) {
    this.config = config;
    this.isCommitMessage = isCommitMessage;

    updateContent(gerritPatchSetFileDiff);
    updateRandomPlaceholder(gerritPatchSetFileDiff);
    log.debug(
        "FileDiffProcessed initialized for {}", (isCommitMessage ? "commit message" : "file diff"));
  }

  private void updateContent(GerritPatchSetFileDiff gerritPatchSetFileDiff) {
    newContent = new ArrayList<>();
    newContent.add("DUMMY LINE #0");
    lineNum = 1;
    reviewDiffContent = new ArrayList<>();
    codeFinderDiffs = new ArrayList<>();
    List<GerritPatchSetFileDiff.Content> patchSetDiffContent = gerritPatchSetFileDiff.getContent();
    log.debug("Updating content from patch set diff content.");
    // Iterate over the items of the diff content
    for (GerritPatchSetFileDiff.Content patchSetContentItem : patchSetDiffContent) {
      diffContentItem = new DiffContent();
      reviewDiffContentItem = new DiffContent();
      charToLineMapItem = new TreeMap<>();
      // Iterate over the fields `a`, `b` and `ab` of each diff content
      for (Field patchSetDiffField : GerritPatchSetFileDiff.Content.class.getDeclaredFields()) {
        processFileDiffItem(patchSetDiffField, patchSetContentItem);
      }
      reviewDiffContent.add(reviewDiffContentItem);
      codeFinderDiffs.add(new CodeFinderDiff(diffContentItem, charToLineMapItem));
    }
  }

  private void updateRandomPlaceholder(GerritPatchSetFileDiff gerritPatchSetFileDiff) {
    int placeholderVariableLength = MIN_RANDOM_PLACEHOLDER_VARIABLE_LENGTH;
    do {
      randomPlaceholder = '#' + RandomStringUtils.random(placeholderVariableLength, true, false);
      placeholderVariableLength++;
    } while (gerritPatchSetFileDiff.toString().contains(randomPlaceholder));
    log.debug("Generated random placeholder: {}", randomPlaceholder);
  }

  private void filterCommitMessageContent(List<String> fieldValue) {
    fieldValue.removeIf(
        s ->
            s.isEmpty()
                || Settings.COMMIT_MESSAGE_FILTER_OUT_PREFIXES.values().stream()
                    .anyMatch(s::startsWith));
    log.debug("Filtered commit message content.");
  }

  private void updateCodeEntities(Field diffField, List<String> diffLines)
      throws IllegalAccessException {
    String diffType = diffField.getName();
    String content = joinWithNewLine(diffLines);
    diffField.set(diffContentItem, content);
    log.debug("Updated code entities for field: {}", diffType);
    // If the lines modified in the PatchSet are not deleted, they are utilized to populate
    // newContent and
    // charToLineMapItem
    if (diffType.contains("b")) {
      int diffCharPointer = -1;
      for (String diffLine : diffLines) {
        // Increase of 1 to take into account of the newline character
        diffCharPointer++;
        charToLineMapItem.put(diffCharPointer, lineNum);
        diffCharPointer += diffLine.length();
        lineNum++;
      }
      // Add the last line to charToLineMapItem
      charToLineMapItem.put(diffCharPointer + 1, lineNum);
      newContent.addAll(diffLines);
    }
    // If the lines modified in the PatchSet are deleted, they are mapped in charToLineMapItem to
    // current lineNum
    else {
      int startingPosition = charToLineMapItem.isEmpty() ? 0 : content.length();
      charToLineMapItem.put(startingPosition, lineNum);
    }

    if (config.getAiFullFileReview() || !diffType.equals("ab")) {
      // Store the new field's value in the diff content for the Patch Set review
      // `reviewDiffContentItem`
      diffField.set(reviewDiffContentItem, content);
    }
  }

  private void processFileDiffItem(
      Field patchSetDiffField, GerritPatchSetFileDiff.Content contentItem) {
    try {
      // Get the `a`, `b` or `ab` field's value from the Patch Set diff content
      @SuppressWarnings("unchecked")
      List<String> diffLines = (List<String>) patchSetDiffField.get(contentItem);
      if (diffLines == null) {
        return;
      }
      if (isCommitMessage) {
        filterCommitMessageContent(diffLines);
      }
      // Get the corresponding `a`, `b` or `ab` field from the DiffContent class
      Field diffField = DiffContent.class.getDeclaredField(patchSetDiffField.getName());
      updateCodeEntities(diffField, diffLines);

    } catch (IllegalAccessException | NoSuchFieldException e) {
      log.error("Error processing file diff item (field: {})", patchSetDiffField.getName(), e);
    }
  }
}
