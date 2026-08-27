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

import com.google.common.annotations.VisibleForTesting;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientPatchSetHelper.*;

@Slf4j
public class GerritClientPatchSetReviewAi extends GerritClientPatchSet
    implements IGerritClientPatchSet {
  private static final Pattern PATCH_DIFF_START = Pattern.compile("(?m)^diff --git ");

  private final GitRepositoryManager repositoryManager;
  private GerritChange change;
  private ChangeSetData changeSetData;

  @Inject
  public GerritClientPatchSetReviewAi(
      Configuration config, GitRepositoryManager repositoryManager) {
    super(config);
    this.repositoryManager = repositoryManager;
  }

  @VisibleForTesting
  public GerritClientPatchSetReviewAi(Configuration config) {
    super(config);
    this.repositoryManager = null;
  }

  public String getPatchSet(ChangeSetData changeSetData, GerritChange change) throws Exception {
    this.change = change;
    this.changeSetData = changeSetData;
    fileDiffsProcessed.clear();
    diffs.clear();
    if (change.getIsCommentEvent()) {
      retrieveRevisionBase(change);
    }

    String formattedPatch = getPatchFromGerrit();
    patchSetFiles = extractFilesFromPatch(formattedPatch);
    if (changeSetData.getSuggestMode()
        && changeSetData.getReviewScope() != ReviewScope.PATCHSET
        && !patchSetFiles.contains("/COMMIT_MSG")) {
      patchSetFiles = new ArrayList<>(patchSetFiles);
      patchSetFiles.add("/COMMIT_MSG");
    }
    log.debug("Files extracted from patch: {}", patchSetFiles);
    retrieveFileDiff(change, revisionBase);

    return formattedPatch;
  }

  @Override
  public String getIncrementalPatchSet(ChangeSetData changeSetData, GerritChange change)
      throws Exception {
    Optional<String> lastReviewedCommit = lastReviewedCommit(changeSetData);
    int patchSetNumber =
        change.getPatchSetAttribute().map(attribute -> attribute.number).orElse(1);
    if (lastReviewedCommit.isEmpty() && patchSetNumber <= 1) {
      return getPatchSet(changeSetData, change);
    }

    this.change = change;
    this.changeSetData = changeSetData;
    try (ManualRequestContext ignored = config.openRequestContext()) {
      ChangeApi changeApi = change.getChangeApi(config);
      RevisionApi currentRevision = changeApi.current();
      String formattedPatch = currentRevision.patch().asString();
      String baseCommit;
      if (lastReviewedCommit.isPresent()) {
        baseCommit = lastReviewedCommit.get();
      } else {
        try {
          RevisionApi previousRevision = changeApi.revision(patchSetNumber - 1);
          CommitInfo previousCommit = previousRevision.commit(false);
          baseCommit = previousCommit.commit;
        } catch (Exception e) {
          log.warn(
              "Could not resolve the previous patch set commit. Using current patch output.",
              e);
          return filterPatch(formattedPatch);
        }
      }
      String incrementalPatch =
          replaceDiffWithIncrementalGitDiff(formattedPatch, baseCommit, currentRevision);
      if (lastReviewedCommit.isPresent()) {
        log.debug(
            "Incremental patch retrieved for change {} from reviewed commit {}"
                + " to patch set {}",
            change.getFullChangeId(),
            baseCommit,
            patchSetNumber);
      } else {
        log.debug(
            "Incremental patch retrieved for change {} between patch sets {} and {}",
            change.getFullChangeId(),
            patchSetNumber - 1,
            patchSetNumber);
      }
      return filterPatch(incrementalPatch);
    }
  }

  private String getPatchFromGerrit() throws Exception {
    try (ManualRequestContext ignored = config.openRequestContext()) {
      RevisionApi currentRevision = change.getChangeApi(config).current();
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
      recordPatchSetRevision(commit);
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

  private String replaceDiffWithIncrementalGitDiff(
      String formattedPatch, String baseCommitId, RevisionApi currentRevision) {
    if (repositoryManager == null) {
      return formattedPatch;
    }
    try {
      CommitInfo currentCommit = currentRevision.commit(false);
      recordPatchSetRevision(currentCommit);
      String incrementalDiff = getCompactGitDiff(baseCommitId, currentCommit.commit);
      if (incrementalDiff.isBlank()) {
        return "";
      }
      return replacePatchDiff(formattedPatch, incrementalDiff);
    } catch (Exception e) {
      log.warn(
          "Could not generate incremental git diff between patch sets. Using current patch output.",
          e);
      return formattedPatch;
    }
  }

  private Optional<String> lastReviewedCommit(ChangeSetData changeSetData) {
    if (changeSetData == null || changeSetData.getPreviousReviewConcernLedger() == null) {
      return Optional.empty();
    }
    String commit = changeSetData.getPreviousReviewConcernLedger().getLastReviewedCommit();
    return Optional.ofNullable(commit).map(String::trim).filter(value -> !value.isEmpty());
  }

  private void recordPatchSetRevision(CommitInfo commit) {
    if (commit != null && commit.commit != null && !commit.commit.isBlank()) {
      change.setPatchSetRevision(commit.commit);
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

  private String getCompactGitDiff(String baseCommitId, String commitId) throws Exception {
    try (Repository repository = repositoryManager.openRepository(change.getProjectNameKey());
        RevWalk revWalk = new RevWalk(repository);
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        DiffFormatter diffFormatter = new DiffFormatter(outputStream)) {
      RevCommit baseCommit = revWalk.parseCommit(ObjectId.fromString(baseCommitId));
      RevCommit commit = revWalk.parseCommit(ObjectId.fromString(commitId));
      diffFormatter.setRepository(repository);
      diffFormatter.setDetectRenames(true);
      diffFormatter.setContext(config.getPatchContextLines());
      RevTree incrementalBaseTree = incrementalBaseTree(repository, revWalk, baseCommit, commit);
      diffFormatter.format(incrementalBaseTree, commit.getTree());
      diffFormatter.flush();
      return outputStream.toString(StandardCharsets.UTF_8);
    }
  }

  private RevTree incrementalBaseTree(
      Repository repository, RevWalk revWalk, RevCommit baseCommit, RevCommit currentCommit)
      throws Exception {
    if (baseCommit.getParentCount() == 0) {
      return currentCommit.getParentCount() == 0
          ? baseCommit.getTree()
          : revWalk.parseCommit(currentCommit.getParent(0)).getTree();
    }

    RevCommit baseParent = revWalk.parseCommit(baseCommit.getParent(0));
    if (currentCommit.getParentCount() == 0) {
      return baseCommit.getTree();
    }
    RevCommit currentParent = revWalk.parseCommit(currentCommit.getParent(0));
    if (baseParent.getTree().equals(currentParent.getTree())) {
      return baseCommit.getTree();
    }

    ThreeWayMerger merger = MergeStrategy.RESOLVE.newMerger(repository, true);
    merger.setBase(baseParent);
    if (merger.merge(currentParent, baseCommit)) {
      return revWalk.parseTree(merger.getResultTreeId());
    }

    log.warn(
        "Could not reapply reviewed commit {} to current base {}."
            + " Using the full current change as the incremental patch.",
        baseCommit.getName(),
        currentParent.getName());
    return currentParent.getTree();
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
