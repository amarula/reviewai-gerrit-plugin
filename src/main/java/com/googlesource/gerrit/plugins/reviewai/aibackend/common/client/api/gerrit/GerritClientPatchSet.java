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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.google.gerrit.extensions.client.ListChangesOption;
import com.google.gerrit.extensions.common.ChangeInfo;
import com.google.gerrit.extensions.common.DiffInfo;
import com.google.gerrit.server.util.ManualRequestContext;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.diff.FileDiffProcessed;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritFileDiff;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritPatchSetFileDiff;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritReviewFileDiff;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.FileUtils.matchesExtensionList;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getNoEscapedGson;
import static java.util.stream.Collectors.toList;

@Slf4j
public class GerritClientPatchSet extends GerritClientAccount {
  protected final List<String> diffs;

  @Getter protected Integer revisionBase = 0;
  @Getter protected List<String> patchSetFiles;

  private boolean isCommitMessage;

  public GerritClientPatchSet(Configuration config) {
    super(config);
    diffs = new ArrayList<>();
    log.debug("Initialized GerritClientPatchSet.");
  }

  public void retrieveRevisionBase(GerritChange change) {
    log.debug("Retrieving revision base for change: {}", change.getFullChangeId());
    try (ManualRequestContext ignored = config.openRequestContext()) {
      ChangeInfo changeInfo =
          config
              .getGerritApi()
              .changes()
              .id(
                  change.getProjectName(),
                  change.getBranchNameKey().shortName(),
                  change.getChangeKey().get())
              .get(ListChangesOption.ALL_REVISIONS);
      revisionBase =
          Optional.ofNullable(changeInfo)
              .map(info -> info.revisions)
              .map(revisions -> revisions.size() - 1)
              .orElse(0);
      log.debug(
          "Retrieved revision base for change: {} is {}", change.getFullChangeId(), revisionBase);
    } catch (Exception e) {
      log.error(
          "Could not retrieve revisions for PatchSet with fullChangeId: {}",
          change.getFullChangeId(),
          e);
      revisionBase = 0;
    }
  }

  protected void retrieveFileDiff(GerritChange change, int revisionBase) throws Exception {
    List<String> enabledFileExtensions = config.getEnabledFileExtensions();
    log.debug("Retrieving file diff for change: {}", change.getFullChangeId());
    try (ManualRequestContext ignored = config.openRequestContext()) {
      for (String filename : patchSetFiles) {
        isCommitMessage = filename.equals("/COMMIT_MSG");
        if (!isCommitMessage && !matchesExtensionList(filename, enabledFileExtensions)) {
          continue;
        }
        DiffInfo diff =
            config
                .getGerritApi()
                .changes()
                .id(
                    change.getProjectName(),
                    change.getBranchNameKey().shortName(),
                    change.getChangeKey().get())
                .current()
                .file(filename)
                .diff(revisionBase);
        processFileDiff(filename, diff);
        log.debug("Processed file diff for file: {}", filename);
      }
    }
  }

  private void processFileDiff(String filename, DiffInfo diff) {
    log.debug("Processing file diff for filename: {}", filename);

    GerritPatchSetFileDiff gerritPatchSetFileDiff = new GerritPatchSetFileDiff();
    Optional.ofNullable(diff.metaA)
        .ifPresent(meta -> gerritPatchSetFileDiff.setMetaA(GerritClientPatchSet.toMeta(meta)));
    Optional.ofNullable(diff.metaB)
        .ifPresent(meta -> gerritPatchSetFileDiff.setMetaB(GerritClientPatchSet.toMeta(meta)));
    Optional.ofNullable(diff.content)
        .ifPresent(
            content ->
                gerritPatchSetFileDiff.setContent(
                    content.stream().map(GerritClientPatchSet::toContent).collect(toList())));

    // Initialize the reduced file diff for the Gerrit review with fields `meta_a` and `meta_b`
    GerritReviewFileDiff gerritReviewFileDiff =
        new GerritReviewFileDiff(
            gerritPatchSetFileDiff.getMetaA(), gerritPatchSetFileDiff.getMetaB());
    FileDiffProcessed fileDiffProcessed =
        new FileDiffProcessed(config, isCommitMessage, gerritPatchSetFileDiff);
    fileDiffsProcessed.put(filename, fileDiffProcessed);
    gerritReviewFileDiff.setContent(fileDiffProcessed.getReviewDiffContent());
    diffs.add(getNoEscapedGson().toJson(gerritReviewFileDiff));
    log.debug("Completed processing for file: {}", filename);
  }

  protected static GerritFileDiff.Meta toMeta(DiffInfo.FileMeta input) {
    GerritFileDiff.Meta meta = new GerritFileDiff.Meta();
    meta.setContentType(input.contentType);
    meta.setName(input.name);
    return meta;
  }

  protected static GerritPatchSetFileDiff.Content toContent(DiffInfo.ContentEntry input) {
    GerritPatchSetFileDiff.Content content = new GerritPatchSetFileDiff.Content();
    content.a = input.a;
    content.b = input.b;
    content.ab = input.ab;
    return content;
  }
}
