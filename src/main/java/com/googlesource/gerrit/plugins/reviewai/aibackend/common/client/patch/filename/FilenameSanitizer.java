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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename;

import static com.googlesource.gerrit.plugins.reviewai.utils.GerritUtils.isGerritNonFilePath;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FilenameSanitizer {
  private final List<String> patchSetFiles;

  public FilenameSanitizer(GerritClient gerritClient, GerritChange change) {
    IGerritClientPatchSet gerritClientPatchSet =
        gerritClient.getClientData(change).getGerritClientPatchSet();
    patchSetFiles = gerritClientPatchSet.getPatchSetFiles();
    log.debug("Initialized Patch set files: {}", patchSetFiles);
  }

  /**
   * Whether a file is part of the patch set under review.
   *
   * <p>The single place the question is answered, so anchoring, sanitizing and concern dismissal
   * all agree. Matching is intentionally loose: the model returns paths as it saw them in the
   * patch, which may be a suffix of the path Gerrit records.
   *
   * <p>A filename is considered part of the patch set when the list is unknown, because dropping
   * every finding whenever the file list is unavailable would lose the review rather than one
   * comment. Callers that must not guess should check for an empty list themselves.
   */
  public boolean isPartOfPatchSet(String filename) {
    if (filename == null || filename.isEmpty()) {
      return false;
    }
    if (isGerritNonFilePath(filename)) {
      // Not a file of the revision: Gerrit's own key for the change or its commit message. Always
      // valid, whatever the file list describes.
      return true;
    }
    if (patchSetFiles == null || patchSetFiles.isEmpty()) {
      return true;
    }
    return patchSetFiles.contains(filename)
        || patchSetFiles.stream().anyMatch(path -> path.contains(filename));
  }

  /**
   * Points a reply item at a file that is part of the patch set, when it can find one.
   *
   * <p>A concern outlives the patch set that raised it, so a reply can name a file that is no
   * longer part of the change — the file was reverted, or its content moved elsewhere. Gerrit
   * rejects a comment anchored to a file that is not in the revision, and one such reply used to
   * fail the whole publication, taking every other finding with it.
   *
   * @return whether the item now names a file in this patch set. False means the finding cannot be
   *     posted as a comment on that file; the caller must not send that path to Gerrit. It used to
   *     warn and return void, which left the invalid filename on the item to be published anyway.
   */
  public boolean sanitizeFilename(AiReplyItem replyItem) {
    String filename = replyItem.getFilename();
    log.debug("Sanitizing filename: {}", filename);
    if (filename == null || filename.isEmpty()) {
      // The model answered without anchoring to a file, which is allowed.
      return true;
    }
    if (isGerritNonFilePath(filename)) {
      // Gerrit's keys for the change and its commit message. They are accepted as-is, and they are
      // not paths that could be missing from the file list - reporting them as unanchored would
      // tell the author a finding was lost when it was not.
      return true;
    }
    if (patchSetFiles == null || patchSetFiles.isEmpty()) {
      // Nothing to validate against. Accepting is the lesser evil here: dropping every finding
      // because the file list is unavailable would lose the review rather than one comment.
      log.debug("No patch set files to validate '{}' against", filename);
      return true;
    }
    if (patchSetFiles.contains(filename)) {
      return true;
    }
    String sanitizedFilename =
        patchSetFiles.stream().filter(s -> s.contains(filename)).findFirst().orElse(null);
    if (sanitizedFilename == null) {
      log.warn(
          "Filename '{}' is not part of this patch set. PatchSet Files: {}",
          filename,
          patchSetFiles);
      return false;
    }
    log.debug("Filename sanitized: {}", sanitizedFilename);
    replyItem.setFilename(sanitizedFilename);
    return true;
  }
}
