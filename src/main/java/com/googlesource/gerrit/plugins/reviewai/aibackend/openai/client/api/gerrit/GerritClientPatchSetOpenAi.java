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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.gerrit;

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientPatchSet;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientPatchSetHelper.*;

@Slf4j
public class GerritClientPatchSetOpenAi extends GerritClientPatchSet
    implements IGerritClientPatchSet {
  private static final Pattern PATCH_DIFF_START = Pattern.compile("(?m)^diff --git ");

  private final GitRepositoryManager repositoryManager;
  private GerritChange change;
  private ChangeSetData changeSetData;

  @Inject
  public GerritClientPatchSetOpenAi(
      Configuration config, AccountCache accountCache, GitRepositoryManager repositoryManager) {
    super(config, accountCache);
    this.repositoryManager = repositoryManager;
  }

  @VisibleForTesting
  public GerritClientPatchSetOpenAi(Configuration config, AccountCache accountCache) {
    super(config, accountCache);
    this.repositoryManager = null;
  }

  public String getPatchSet(ChangeSetData changeSetData, GerritChange change) throws Exception {
    this.change = change;
    this.changeSetData = changeSetData;
    if (change.getIsCommentEvent()) {
      retrieveRevisionBase(change);
    }

    String formattedPatch = getPatchFromGerrit();
    patchSetFiles = extractFilesFromPatch(formattedPatch);
    log.debug("Files extracted from patch: {}", patchSetFiles);
    retrieveFileDiff(change, revisionBase);

    return formattedPatch;
  }

  private String getPatchFromGerrit() throws Exception {
    try (ManualRequestContext requestContext = config.openRequestContext()) {
      RevisionApi currentRevision =
          config
              .getGerritApi()
              .changes()
              .id(
                  change.getProjectName(),
                  change.getBranchNameKey().shortName(),
                  change.getChangeKey().get())
              .current();
      String formattedPatch = currentRevision.patch().asString();
      log.debug("Formatted Patch retrieved: {}", formattedPatch);

      return filterPatch(replaceDiffWithCompactGitDiff(formattedPatch, currentRevision));
    }
  }

  private String replaceDiffWithCompactGitDiff(String formattedPatch, RevisionApi currentRevision) {
    if (repositoryManager == null) {
      return formattedPatch;
    }
    try {
      CommitInfo commit = currentRevision.commit(false);
      String compactDiff = getCompactGitDiff(commit.commit);
      if (compactDiff.isBlank()) {
        return formattedPatch;
      }
      return replacePatchDiff(formattedPatch, compactDiff);
    } catch (Exception e) {
      log.warn("Could not generate compact git diff for patch set. Using Gerrit patch output.", e);
      return formattedPatch;
    }
  }

  private String getCompactGitDiff(String commitId) throws Exception {
    try (Repository repository = repositoryManager.openRepository(change.getProjectNameKey());
        RevWalk revWalk = new RevWalk(repository);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DiffFormatter diffFormatter = new DiffFormatter(outputStream)) {
      RevCommit commit = revWalk.parseCommit(ObjectId.fromString(commitId));
      diffFormatter.setRepository(repository);
      diffFormatter.setDetectRenames(true);
      diffFormatter.setContext(config.getPatchContextLines());

      if (commit.getParentCount() == 0) {
        formatRootCommitDiff(repository, commit, diffFormatter);
      } else {
        RevCommit parent = revWalk.parseCommit(commit.getParent(0).getId());
        RevTree parentTree = parent.getTree();
        diffFormatter.format(parentTree, commit.getTree());
      }

      diffFormatter.flush();
      return outputStream.toString(StandardCharsets.UTF_8);
    }
  }

  private static void formatRootCommitDiff(
      Repository repository, RevCommit commit, DiffFormatter diffFormatter) throws Exception {
    try (ObjectReader reader = repository.newObjectReader()) {
      CanonicalTreeParser newTreeParser = new CanonicalTreeParser();
      newTreeParser.reset(reader, commit.getTree());
      diffFormatter.format(new EmptyTreeIterator(), newTreeParser);
    }
  }

  private static String replacePatchDiff(String formattedPatch, String compactDiff) {
    Matcher diffStartMatcher = PATCH_DIFF_START.matcher(formattedPatch);
    if (!diffStartMatcher.find()) {
      return formattedPatch;
    }

    return formattedPatch.substring(0, diffStartMatcher.start()) + compactDiff;
  }

  private String filterPatch(String formattedPatch) {
    if (changeSetData.getReviewScope() != null) {
      return filterPatchByReviewScope(formattedPatch);
    }
    if (config.getAiReviewCommitMessages()) {
      String patchWithCommitMessage =
          filterPatchByEnabledFileExtensions(
              filterPatchWithCommitMessage(formattedPatch), config.getEnabledFileExtensions());
      log.debug("Patch filtered to include commit messages: {}", patchWithCommitMessage);
      return patchWithCommitMessage;
    } else {
      String patchWithoutCommitMessage =
          filterPatchByEnabledFileExtensions(
              filterPatchWithoutCommitMessage(change, formattedPatch),
              config.getEnabledFileExtensions());
      log.debug("Patch filtered to exclude commit messages: {}", patchWithoutCommitMessage);
      return patchWithoutCommitMessage;
    }
  }

  private String filterPatchByReviewScope(String formattedPatch) {
    return switch (changeSetData.getReviewScope()) {
      case FULL -> {
        String fullPatch =
            filterPatchByEnabledFileExtensions(
                filterPatchWithCommitMessage(formattedPatch), config.getEnabledFileExtensions());
        log.debug("Patch filtered by command scope to include the full Change Set: {}", fullPatch);
        yield fullPatch;
      }
      case PATCHSET -> {
        String patchWithoutCommitMessage =
            filterPatchByEnabledFileExtensions(
                filterPatchWithoutCommitMessage(change, formattedPatch),
                config.getEnabledFileExtensions());
        log.debug(
            "Patch filtered by command scope to exclude commit messages: {}",
            patchWithoutCommitMessage);
        yield patchWithoutCommitMessage;
      }
      case COMMIT_MESSAGE -> {
        String patchWithCommitMessage =
            filterPatchByEnabledFileExtensions(
                filterPatchWithCommitMessage(formattedPatch), config.getEnabledFileExtensions());
        log.debug(
            "Patch filtered by command scope to include commit message and patch context: {}",
            patchWithCommitMessage);
        yield patchWithCommitMessage;
      }
    };
  }
}
