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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.google.gerrit.entities.BranchNameKey;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.Test;

public class GitRepoFilesTest extends TestBase {
  private static final String SPECIALIZED_BRANCH = "release/device-a";
  private static final Path SPECIALIZED_BRANCH_CONTENT =
      Paths.get("src/test/resources/__files/git/specializedBranch.txt");

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
    Files.copy(SPECIALIZED_BRANCH_CONTENT, workTree.resolve(SPECIALIZED_BRANCH_CONTENT.getFileName()));
    git.add().addFilepattern(SPECIALIZED_BRANCH_CONTENT.getFileName().toString()).call();
    return git.commit()
        .setMessage("Add specialized branch content")
        .setAuthor("Test", "test@example.com")
        .call();
  }
}
