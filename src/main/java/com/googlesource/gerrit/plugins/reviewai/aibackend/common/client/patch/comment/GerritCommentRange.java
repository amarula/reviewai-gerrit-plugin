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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.comment;

import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.patch.InlineCode;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.diff.FileDiffProcessed;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritCodeRange;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Optional;

@Slf4j
public class GerritCommentRange {
  private final HashMap<String, FileDiffProcessed> fileDiffsProcessed;

  public GerritCommentRange(GerritClient gerritClient, GerritChange change) {
    log.debug("Initialized GerritCommentRange for change '{}'", change.getFullChangeId());
    IGerritClientPatchSet gerritClientPatchSet =
        gerritClient.getClientData(change).getGerritClientPatchSet();
    fileDiffsProcessed = gerritClientPatchSet.getFileDiffsProcessed();
    log.debug("Initialized File Diffs processed : {}", fileDiffsProcessed);
  }

  public Optional<GerritCodeRange> getGerritCommentRange(AiReplyItem replyItem) {
    log.debug("Retrieving Gerrit comment range for reply item: {}", replyItem);
    Optional<GerritCodeRange> gerritCommentRange = Optional.empty();
    String filename = replyItem.getFilename();
    if (filename == null) {
      log.debug("Filename is null, skipping code range extraction.");
      return gerritCommentRange;
    }
    if (replyItem.getCodeSnippet() == null) {
      log.info("CodeSnippet is null in reply '{}'.", replyItem);
      return gerritCommentRange;
    }
    if (!fileDiffsProcessed.containsKey(filename)) {
      log.info(
          "Filename '{}' not found for reply '{}'.\nFileDiffsProcessed = {}",
          filename,
          replyItem,
          fileDiffsProcessed);
      return gerritCommentRange;
    }
    if (filename.equals("/COMMIT_MSG")) {
      return fileDiffsProcessed.get(filename).getCommitMessageRange();
    }
    InlineCode inlineCode = new InlineCode(fileDiffsProcessed.get(filename));
    gerritCommentRange = inlineCode.findCommentRange(replyItem);
    if (gerritCommentRange.isEmpty()) {
      log.info("Inline code not found for reply {}", replyItem);
    } else {
      log.debug("Found inline code range: {}", gerritCommentRange.get());
    }
    return gerritCommentRange;
  }
}
