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

import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.FileApi;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.common.CommitInfo;
import com.google.gerrit.extensions.common.DiffInfo;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class GerritClientPatchSetReviewAiTest extends TestBase {
  private static final Path TEST_RESOURCES_PATH = TestResourceLoader.getTestResourcePath();
  private static final String VERBOSE_RENAME_PATCH_FILE =
      "__files/openai/gerritVerboseRenamePatch.txt";
  private static final String MIXED_EXTENSION_PATCH_FILE =
      "__files/openai/mixedExtensionPatch.txt";
  private static final String INCREMENTAL_CURRENT_PATCH_FILE =
      "__files/openai/incrementalCurrentPatch.txt";
  private static final String CONTEXT_LINES_PATCH_FILE =
      "__files/openai/gerritContextLinesPatch.txt";
  private static final String CONTEXT_PATCH_ORIGINAL_FILE =
      "__files/openai/contextPatchOriginal.py";
  private static final String CONTEXT_PATCH_MODIFIED_FILE =
      "__files/openai/contextPatchModified.py";

  @Mock private Configuration config;
  @Mock private GitRepositoryManager repositoryManager;
  @Mock private GerritApi gerritApi;
  @Mock private Changes changes;
  @Mock private ChangeApi changeApi;
  @Mock private RevisionApi revisionApi;
  @Mock private RevisionApi previousRevisionApi;
  @Mock private FileApi fileApi;
  private Path gitDir;

  @Test
  public void getPatchSetUsesCompactGitRenameDiff() throws Exception {
    RevCommit renameCommit = createRenameCommit();
    mockGerritPatch(renameCommit);

    GerritClientPatchSetReviewAi client =
        new GerritClientPatchSetReviewAi(config, repositoryManager);
    String patchSet = client.getPatchSet(new ChangeSetData(1), getGerritChange());

    Assert.assertTrue(patchSet.contains("diff --git a/old_name.py b/new_name.py"));
    Assert.assertTrue(patchSet.contains("similarity index 100%"));
    Assert.assertTrue(patchSet.contains("rename from old_name.py"));
    Assert.assertTrue(patchSet.contains("rename to new_name.py"));
    Assert.assertFalse(patchSet.contains("deleted file mode"));
    Assert.assertFalse(patchSet.contains("new file mode"));
    Assert.assertEquals(List.of("new_name.py"), client.getPatchSetFiles());
  }

  @Test
  public void getPatchSetUsesConfiguredPatchContextLines() throws Exception {
    RevCommit modifyCommit = createModifyCommit();
    mockGerritPatch(modifyCommit, getContextLinesPatch(), "context.py", 0);

    GerritClientPatchSetReviewAi client =
        new GerritClientPatchSetReviewAi(config, repositoryManager);
    String patchSet = client.getPatchSet(new ChangeSetData(1), getGerritChange());

    String[] originalLines = getContextPatchOriginal().split("\\R");
    String[] modifiedLines = getContextPatchModified().split("\\R");
    Assert.assertTrue(patchSet.contains("-" + originalLines[2]));
    Assert.assertTrue(patchSet.contains("+" + modifiedLines[2]));
    Assert.assertFalse(patchSet.contains(" " + originalLines[1]));
    Assert.assertFalse(patchSet.contains(" " + originalLines[3]));
    Assert.assertEquals(List.of("context.py"), client.getPatchSetFiles());
  }

  @Test
  public void getPatchSetExcludesFilesOutsideEnabledExtensions() throws Exception {
    when(config.getGerritApi()).thenReturn(gerritApi);
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id(PROJECT_NAME.get(), BRANCH_NAME.shortName(), CHANGE_ID.get()))
        .thenReturn(changeApi);
    when(changeApi.current()).thenReturn(revisionApi);
    when(revisionApi.patch()).thenReturn(BinaryResult.create(getMixedExtensionPatch()));
    when(config.getAiReviewCommitMessages()).thenReturn(false);
    when(config.getEnabledFileExtensions()).thenReturn(List.of("py"));

    when(revisionApi.file("allowed.py")).thenReturn(fileApi);
    DiffInfo diffInfo = new DiffInfo();
    diffInfo.content = new ArrayList<>();
    when(fileApi.diff(0)).thenReturn(diffInfo);

    GerritClientPatchSetReviewAi client = new GerritClientPatchSetReviewAi(config);
    String patchSet = client.getPatchSet(new ChangeSetData(1), getGerritChange());

    Assert.assertTrue(patchSet.contains("diff --git a/allowed.py b/allowed.py"));
    Assert.assertFalse(patchSet.contains("diff --git a/ignored.txt b/ignored.txt"));
    Assert.assertFalse(patchSet.contains("ignored change"));
    Assert.assertEquals(List.of("allowed.py"), client.getPatchSetFiles());
  }

  @Test
  public void getIncrementalPatchSetUsesPreviousPatchSetAsBase() throws Exception {
    List<RevCommit> patchSetCommits = createIncrementalPatchSetCommits();
    when(config.getGerritApi()).thenReturn(gerritApi);
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id(PROJECT_NAME.get(), BRANCH_NAME.shortName(), CHANGE_ID.get()))
        .thenReturn(changeApi);
    when(changeApi.current()).thenReturn(revisionApi);
    when(changeApi.revision(2)).thenReturn(previousRevisionApi);
    when(revisionApi.patch()).thenReturn(BinaryResult.create(getIncrementalCurrentPatch()));
    when(previousRevisionApi.commit(false)).thenReturn(commitInfo(patchSetCommits.get(0)));
    when(revisionApi.commit(false)).thenReturn(commitInfo(patchSetCommits.get(1)));
    when(repositoryManager.openRepository(any()))
        .thenAnswer(
            invocation ->
                new FileRepositoryBuilder()
                    .setGitDir(gitDir.toFile())
                    .setMustExist(true)
                    .build());
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    when(config.getEnabledFileExtensions()).thenReturn(List.of("py"));
    when(config.getPatchContextLines()).thenReturn(3);
    GerritChange change = getGerritChange();
    change.setPatchSetNumber(3);

    GerritClientPatchSetReviewAi client =
        new GerritClientPatchSetReviewAi(config, repositoryManager);
    String patchSet = client.getIncrementalPatchSet(new ChangeSetData(1), change);

    verify(changeApi).revision(2);
    verify(revisionApi).patch();
    verify(revisionApi, never()).patch("2");
    Assert.assertTrue(patchSet.contains("Subject: Patch set 3"));
    Assert.assertTrue(patchSet.contains("diff --git a/allowed.py b/allowed.py"));
    Assert.assertFalse(patchSet.contains("diff --git a/ignored.txt b/ignored.txt"));
    Assert.assertTrue(patchSet.contains("-print('before')"));
    Assert.assertFalse(patchSet.contains("-print('base')"));
    Assert.assertTrue(patchSet.contains("+print('after')"));
  }

  @Test
  public void getIncrementalPatchSetIsEmptyWhenOnlyCommitMessageChanges() throws Exception {
    List<RevCommit> patchSetCommits = createCommitMessageOnlyPatchSetCommits();
    when(config.getGerritApi()).thenReturn(gerritApi);
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id(PROJECT_NAME.get(), BRANCH_NAME.shortName(), CHANGE_ID.get()))
        .thenReturn(changeApi);
    when(changeApi.current()).thenReturn(revisionApi);
    when(changeApi.revision(2)).thenReturn(previousRevisionApi);
    when(revisionApi.patch()).thenReturn(BinaryResult.create(getIncrementalCurrentPatch()));
    when(previousRevisionApi.commit(false)).thenReturn(commitInfo(patchSetCommits.get(0)));
    when(revisionApi.commit(false)).thenReturn(commitInfo(patchSetCommits.get(1)));
    when(repositoryManager.openRepository(any()))
        .thenAnswer(
            invocation ->
                new FileRepositoryBuilder()
                    .setGitDir(gitDir.toFile())
                    .setMustExist(true)
                    .build());
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    when(config.getEnabledFileExtensions()).thenReturn(List.of("py"));
    when(config.getPatchContextLines()).thenReturn(3);
    GerritChange change = getGerritChange();
    change.setPatchSetNumber(3);

    GerritClientPatchSetReviewAi client =
        new GerritClientPatchSetReviewAi(config, repositoryManager);

    Assert.assertEquals("", client.getIncrementalPatchSet(new ChangeSetData(1), change));
  }

  private List<RevCommit> createIncrementalPatchSetCommits() throws Exception {
    try (Git git = Git.init().setDirectory(tempFolder.newFolder("incremental-repo")).call()) {
      gitDir = git.getRepository().getDirectory().toPath();
      Path workTree = git.getRepository().getWorkTree().toPath();
      Files.writeString(workTree.resolve("allowed.py"), "print('before')\n");
      Files.writeString(workTree.resolve("ignored.txt"), "unchanged\n");
      git.add().addFilepattern(".").call();
      RevCommit previousPatchSet =
          git.commit().setMessage("Patch set 2").setAuthor("Test", "test@example.com").call();

      Files.writeString(workTree.resolve("allowed.py"), "print('after')\n");
      git.add().addFilepattern("allowed.py").call();
      RevCommit currentPatchSet =
          git.commit().setMessage("Patch set 3").setAuthor("Test", "test@example.com").call();
      return List.of(previousPatchSet, currentPatchSet);
    }
  }

  private List<RevCommit> createCommitMessageOnlyPatchSetCommits() throws Exception {
    try (Git git =
        Git.init().setDirectory(tempFolder.newFolder("commit-message-only-repo")).call()) {
      gitDir = git.getRepository().getDirectory().toPath();
      Path workTree = git.getRepository().getWorkTree().toPath();
      Files.writeString(workTree.resolve("allowed.py"), "print('unchanged')\n");
      git.add().addFilepattern("allowed.py").call();
      RevCommit previousPatchSet =
          git.commit().setMessage("Patch set 2").setAuthor("Test", "test@example.com").call();
      RevCommit currentPatchSet =
          git.commit()
              .setMessage("Patch set 3")
              .setAuthor("Test", "test@example.com")
              .setAllowEmpty(true)
              .call();
      return List.of(previousPatchSet, currentPatchSet);
    }
  }

  private CommitInfo commitInfo(RevCommit commit) {
    CommitInfo commitInfo = new CommitInfo();
    commitInfo.commit = commit.getName();
    return commitInfo;
  }

  private RevCommit createRenameCommit() throws Exception {
    try (Git git = Git.init().setDirectory(tempFolder.newFolder("repo")).call()) {
      gitDir = git.getRepository().getDirectory().toPath();
      Path workTree = git.getRepository().getWorkTree().toPath();

      Files.writeString(workTree.resolve("old_name.py"), "print('same content')\n");
      git.add().addFilepattern("old_name.py").call();
      git.commit().setMessage("Add file").setAuthor("Test", "test@example.com").call();

      Files.move(workTree.resolve("old_name.py"), workTree.resolve("new_name.py"));
      git.rm().addFilepattern("old_name.py").call();
      git.add().addFilepattern("new_name.py").call();
      return git.commit().setMessage("Rename file").setAuthor("Test", "test@example.com").call();
    }
  }

  private RevCommit createModifyCommit() throws Exception {
    try (Git git = Git.init().setDirectory(tempFolder.newFolder("repo")).call()) {
      gitDir = git.getRepository().getDirectory().toPath();
      Path workTree = git.getRepository().getWorkTree().toPath();

      Files.writeString(workTree.resolve("context.py"), getContextPatchOriginal());
      git.add().addFilepattern("context.py").call();
      git.commit().setMessage("Add context file").setAuthor("Test", "test@example.com").call();

      Files.writeString(workTree.resolve("context.py"), getContextPatchModified());
      git.add().addFilepattern("context.py").call();
      return git.commit()
          .setMessage("Modify context file")
          .setAuthor("Test", "test@example.com")
          .call();
    }
  }

  private void mockGerritPatch(RevCommit renameCommit) throws Exception {
    mockGerritPatch(renameCommit, getVerboseRenamePatch(), "new_name.py", 3);
  }

  private void mockGerritPatch(
      RevCommit commit, String formattedPatch, String fileName, int patchContextLines)
      throws Exception {
    when(config.getGerritApi()).thenReturn(gerritApi);
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id(PROJECT_NAME.get(), BRANCH_NAME.shortName(), CHANGE_ID.get()))
        .thenReturn(changeApi);
    when(changeApi.current()).thenReturn(revisionApi);
    when(revisionApi.patch()).thenReturn(BinaryResult.create(formattedPatch));

    CommitInfo commitInfo = new CommitInfo();
    commitInfo.commit = commit.getName();
    when(revisionApi.commit(false)).thenReturn(commitInfo);

    when(repositoryManager.openRepository(any()))
        .thenAnswer(
            invocation ->
                new FileRepositoryBuilder().setGitDir(gitDir.toFile()).setMustExist(true).build());

    when(config.getEnabledFileExtensions()).thenReturn(List.of("py"));
    when(config.getPatchContextLines()).thenReturn(patchContextLines);
    when(revisionApi.file(fileName)).thenReturn(fileApi);
    DiffInfo diffInfo = new DiffInfo();
    diffInfo.content = new ArrayList<>();
    when(fileApi.diff(0)).thenReturn(diffInfo);
  }

  private String getVerboseRenamePatch() throws Exception {
    return Files.readString(TEST_RESOURCES_PATH.resolve(VERBOSE_RENAME_PATCH_FILE));
  }

  private String getMixedExtensionPatch() throws Exception {
    return Files.readString(TEST_RESOURCES_PATH.resolve(MIXED_EXTENSION_PATCH_FILE));
  }

  private String getIncrementalCurrentPatch() throws Exception {
    return Files.readString(TEST_RESOURCES_PATH.resolve(INCREMENTAL_CURRENT_PATCH_FILE));
  }

  private String getContextLinesPatch() throws Exception {
    return Files.readString(TEST_RESOURCES_PATH.resolve(CONTEXT_LINES_PATCH_FILE));
  }

  private String getContextPatchOriginal() throws Exception {
    return Files.readString(TEST_RESOURCES_PATH.resolve(CONTEXT_PATCH_ORIGINAL_FILE));
  }

  private String getContextPatchModified() throws Exception {
    return Files.readString(TEST_RESOURCES_PATH.resolve(CONTEXT_PATCH_MODIFIED_FILE));
  }
}
