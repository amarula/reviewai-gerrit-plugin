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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

public class GitRepoFilesTest extends TestBase {
  private static final String SPECIALIZED_BRANCH = "release/device-a";
  private static final int CHANGE_NUMBER = 12345;
  private static final int PATCH_SET_NUMBER = 7;
  private static final String PATCH_SET_REF = "refs/changes/45/12345/7";
  private static final Path SPECIALIZED_BRANCH_CONTENT =
      TestResourceLoader.getTestResourcePath().resolve("__files/git/specializedBranch.txt");

  @Test
  public void getBranchRevTreeUsesChangeTargetBranch() throws Exception {
    try (Git git = createRepository()) {
      RevCommit specializedBranchCommit = commitSpecializedBranchContent(git);
      GerritChange change =
          new GerritChange(
              PROJECT_NAME, BranchNameKey.create(PROJECT_NAME, SPECIALIZED_BRANCH), CHANGE_ID);

      assertEquals(
          specializedBranchCommit.getTree(),
          new GitRepoFiles().getBranchRevTree(git.getRepository(), change));
    }
  }

  @Test
  public void getBranchRevTreeFailsClearlyWhenChangeTargetBranchDoesNotExist() throws Exception {
    try (Git git = createRepository()) {
      GerritChange change =
          new GerritChange(
              PROJECT_NAME, BranchNameKey.create(PROJECT_NAME, "missing-branch"), CHANGE_ID);

      IOException exception =
          assertThrows(
              IOException.class,
              () -> new GitRepoFiles().getBranchRevTree(git.getRepository(), change));

      assertEquals("Branch not found: refs/heads/missing-branch", exception.getMessage());
    }
  }

  @Test
  public void getPatchSetRevTreeUsesCurrentPatchSetRef() throws Exception {
    try (Git git = createRepository()) {
      RevCommit patchSetCommit = commitSpecializedBranchContent(git);
      RefUpdate patchSetRef = git.getRepository().updateRef(PATCH_SET_REF);
      patchSetRef.setNewObjectId(patchSetCommit);
      assertEquals(RefUpdate.Result.NEW, patchSetRef.update());
      GerritChange change = getGerritChange();
      change.setChangeNumber(CHANGE_NUMBER);
      change.setPatchSetNumber(PATCH_SET_NUMBER);

      assertEquals(
          patchSetCommit.getTree(),
          new GitRepoFiles().getPatchSetRevTree(git.getRepository(), change));
    }
  }

  @Test
  public void getPatchSetChangedFilesReturnsPathsChangedByPatchSet() throws Exception {
    try (Git git = createRepository()) {
      Path workTree = git.getRepository().getWorkTree().toPath();
      Files.writeString(workTree.resolve("modified.py"), "original\n");
      Files.writeString(workTree.resolve("deleted.py"), "to be deleted\n");
      git.add().addFilepattern(".").call();
      git.commit().setMessage("Base files").setAuthor("Test", "test@example.com").call();

      Files.writeString(workTree.resolve("modified.py"), "modified\n");
      Files.writeString(workTree.resolve("added.py"), "new file\n");
      git.rm().addFilepattern("deleted.py").call();
      git.add().addFilepattern("modified.py").call();
      git.add().addFilepattern("added.py").call();
      RevCommit patchSetCommit =
          git.commit().setMessage("Patch set").setAuthor("Test", "test@example.com").call();

      RefUpdate patchSetRef = git.getRepository().updateRef(PATCH_SET_REF);
      patchSetRef.setNewObjectId(patchSetCommit);
      assertEquals(RefUpdate.Result.NEW, patchSetRef.update());

      GerritChange change = getGerritChange();
      change.setChangeNumber(CHANGE_NUMBER);
      change.setPatchSetNumber(PATCH_SET_NUMBER);

      GitRepositoryManager repositoryManager = mock(GitRepositoryManager.class);
      when(repositoryManager.openRepository(any(Project.NameKey.class)))
          .thenReturn(git.getRepository());

      assertEquals(
          Set.of("added.py", "deleted.py", "modified.py"),
          new GitRepoFiles(repositoryManager).getPatchSetChangedFiles(change));
    }
  }

  @Test
  public void grepPatchSetFiltersExactPathContainingColon() throws Exception {
    try (Git git = createRepository()) {
      String changedPath = "specialized:branch.py";
      Path workTree = git.getRepository().getWorkTree().toPath();
      Files.copy(SPECIALIZED_BRANCH_CONTENT, workTree.resolve(changedPath));
      git.add().addFilepattern(changedPath).call();
      RevCommit patchSetCommit =
          git.commit().setMessage("Add changed file").setAuthor("Test", "test@example.com").call();

      RefUpdate patchSetRef = git.getRepository().updateRef(PATCH_SET_REF);
      patchSetRef.setNewObjectId(patchSetCommit);
      assertEquals(RefUpdate.Result.NEW, patchSetRef.update());

      GerritChange change = getGerritChange();
      change.setChangeNumber(CHANGE_NUMBER);
      change.setPatchSetNumber(PATCH_SET_NUMBER);

      GitRepositoryManager repositoryManager = mock(GitRepositoryManager.class);
      when(repositoryManager.openRepository(any(Project.NameKey.class)))
          .thenReturn(git.getRepository());
      Configuration config = mock(Configuration.class);
      when(config.getEnabledFileExtensions()).thenReturn(List.of("py"));

      assertEquals(
          List.of(changedPath + ":1: specialized branch content"),
          new GitRepoFiles(repositoryManager)
              .grepPatchSet(config, change, "specialized", Set.of(changedPath)));
    }
  }

  @Test
  public void concurrentCallsDoNotShareFileExtensionFilters() throws Exception {
    // One GitRepoFiles instance serves every agent stage running against a change, so its calls
    // overlap. If the extension filters are held on the instance rather than per call, one call's
    // filters decide another call's results - silently, and only under concurrency.
    try (Git git = createRepository()) {
      Path workTree = git.getRepository().getWorkTree().toPath();
      Files.writeString(workTree.resolve("wanted.py"), "shared marker\n");
      Files.writeString(workTree.resolve("other.txt"), "shared marker\n");
      git.add().addFilepattern(".").call();
      RevCommit patchSetCommit =
          git.commit().setMessage("Two file types").setAuthor("Test", "test@example.com").call();

      RefUpdate patchSetRef = git.getRepository().updateRef(PATCH_SET_REF);
      patchSetRef.setNewObjectId(patchSetCommit);
      assertEquals(RefUpdate.Result.NEW, patchSetRef.update());

      GerritChange change = getGerritChange();
      change.setChangeNumber(CHANGE_NUMBER);
      change.setPatchSetNumber(PATCH_SET_NUMBER);

      CountDownLatch firstCallAtRepository = new CountDownLatch(1);
      CountDownLatch secondCallFinished = new CountDownLatch(1);
      AtomicBoolean firstCall = new AtomicBoolean(true);

      GitRepositoryManager repositoryManager = mock(GitRepositoryManager.class);
      when(repositoryManager.openRepository(any(Project.NameKey.class)))
          .thenAnswer(
              invocation -> {
                if (firstCall.compareAndSet(true, false)) {
                  // Reached only after this call has taken its configuration, and before it walks
                  // the tree. Holding it here lets the other call run start to finish in between,
                  // which makes the interleaving deterministic instead of a race the test hopes to
                  // hit.
                  firstCallAtRepository.countDown();
                  secondCallFinished.await(5, TimeUnit.SECONDS);
                }
                return git.getRepository();
              });

      GitRepoFiles files = new GitRepoFiles(repositoryManager);
      ExecutorService pool = Executors.newFixedThreadPool(2);
      try {
        Future<List<String>> pythonMatches =
            pool.submit(
                () ->
                    files.grepPatchSet(configWithExtensions("py"), change, "shared marker", null));
        assertTrue(firstCallAtRepository.await(5, TimeUnit.SECONDS));

        Future<List<String>> textMatches =
            pool.submit(
                () ->
                    files.grepPatchSet(configWithExtensions("txt"), change, "shared marker", null));
        List<String> texts = textMatches.get(5, TimeUnit.SECONDS);
        secondCallFinished.countDown();
        List<String> pythons = pythonMatches.get(5, TimeUnit.SECONDS);

        assertFalse("the .py call should have matched something", pythons.isEmpty());
        assertFalse("the .txt call should have matched something", texts.isEmpty());
        assertTrue(
            "a .py-only call returned .txt files: " + pythons, allStartWith(pythons, "wanted.py"));
        assertTrue(
            "a .txt-only call returned .py files: " + texts, allStartWith(texts, "other.txt"));
      } finally {
        pool.shutdownNow();
      }
    }
  }

  private static boolean allStartWith(List<String> matches, String path) {
    return matches.stream().allMatch(match -> match.startsWith(path + ":"));
  }

  private static Configuration configWithExtensions(String extension) {
    Configuration config = mock(Configuration.class);
    when(config.getEnabledFileExtensions()).thenReturn(List.of(extension));
    when(config.getDisabledFileExtensions()).thenReturn(List.of());
    return config;
  }

  private Git createRepository() throws Exception {
    Git git = Git.init().setDirectory(tempFolder.newFolder("repo")).call();
    git.commit()
        .setAllowEmpty(true)
        .setMessage("Initial commit")
        .setAuthor("Test", "test@example.com")
        .call();
    return git;
  }

  private RevCommit commitSpecializedBranchContent(Git git) throws Exception {
    git.branchCreate().setName(SPECIALIZED_BRANCH).call();
    git.checkout().setName(SPECIALIZED_BRANCH).call();
    Path workTree = git.getRepository().getWorkTree().toPath();
    Files.copy(
        SPECIALIZED_BRANCH_CONTENT, workTree.resolve(SPECIALIZED_BRANCH_CONTENT.getFileName()));
    git.add().addFilepattern(SPECIALIZED_BRANCH_CONTENT.getFileName().toString()).call();
    return git.commit()
        .setMessage("Add specialized branch content")
        .setAuthor("Test", "test@example.com")
        .call();
  }
}
