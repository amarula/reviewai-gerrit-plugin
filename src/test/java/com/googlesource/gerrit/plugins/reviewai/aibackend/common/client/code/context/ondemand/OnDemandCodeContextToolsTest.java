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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class OnDemandCodeContextToolsTest extends TestBase {
  private static final Path BASE_PATH = TestResourceLoader.getTestResourcePath();
  private static final String CONTEXT_FILE = "__files/openai/contextPatchOriginal.py";
  private static final String SMALL_TREE_FILE = "__files/ondemand/treeSmall.txt";
  private static final String LARGE_TREE_FILE = "__files/ondemand/treeLarge.txt";

  @Mock private Configuration config;
  @Mock private GitRepoFiles gitRepoFiles;

  private GerritChange change;
  private OnDemandCodeContextTools tools;

  @Before
  public void setUp() {
    change = getGerritChange();
    tools = new OnDemandCodeContextTools(config, change, gitRepoFiles);
  }

  @Test
  public void treeReturnsRepositoryPathsFromSubdir() throws Exception {
    List<String> paths = readTestFileLines(SMALL_TREE_FILE);
    when(gitRepoFiles.getPatchSetFileTree(config, change, "src")).thenReturn(paths);
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);

    String output = tools.execute("tree", "{\"subdir\":\"src\"}");

    // Without a resolved change the tree spans the repository, so it is marked as project-scoped
    // context.
    assertEquals(
        OnDemandCodeContextTools.PROJECT_SCOPE_HEADER + "\n" + String.join("\n", paths), output);
  }

  @Test
  public void treeCompressesLargeRepositoryPaths() throws Exception {
    when(gitRepoFiles.getPatchSetFileTree(config, change, null))
        .thenReturn(readTestFileLines(LARGE_TREE_FILE));
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);

    String output = tools.execute("tree", "{}");

    assertEquals(
        OnDemandCodeContextTools.PROJECT_SCOPE_HEADER + "\ndocs/README.md\nsrc/...", output);
    assertTrue(
        output.length()
            <= TreeOutputCompressor.DEFAULT_MAX_LENGTH
                + OnDemandCodeContextTools.PROJECT_SCOPE_HEADER.length()
                + 1);
  }

  @Test
  public void getContentReturnsFileContentFromProjectRoot() throws Exception {
    String content = readTestFile(CONTEXT_FILE);
    when(gitRepoFiles.getPatchSetFileContent(change, "context.py")).thenReturn(content);
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);

    String output = tools.execute("get_content", "{\"file_path\":\"context.py\"}");

    assertEquals(content, output);
  }

  @Test
  public void getContentRejectsVirtualCommitMessagePaths() {
    assertEquals(
        "CONTEXT NOT PROVIDED", tools.execute("get_content", "{\"file_path\":\"COMMIT_MSG\"}"));
    assertEquals(
        "CONTEXT NOT PROVIDED", tools.execute("get_content", "{\"file_path\":\"/COMMIT_MSG\"}"));
    assertEquals(
        "CONTEXT NOT PROVIDED",
        tools.execute("get_content", "{\"file_path\":\"reviewai-topic-change-1/COMMIT_MSG\"}"));
    verifyNoInteractions(gitRepoFiles);
  }

  @Test
  public void getContentAllowsCommitMessageFilenameInRepositorySubdirectory() throws Exception {
    String content = readTestFile(CONTEXT_FILE);
    when(gitRepoFiles.getPatchSetFileContent(change, "docs/COMMIT_MSG")).thenReturn(content);
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);

    String output = tools.execute("get_content", "{\"file_path\":\"docs/COMMIT_MSG\"}");

    assertEquals(content, output);
  }

  @Test
  public void grepReturnsMatches() throws Exception {
    String firstLine = readTestFile(CONTEXT_FILE).split("\\R", 2)[0];
    String match = "context.py:1: " + firstLine;
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(null);
    when(gitRepoFiles.grepPatchSet(config, change, "typing", null)).thenReturn(List.of(match));

    String output = tools.execute("grep", "{\"string\":\"typing\"}");

    // Without a resolved change the search spans the repository, so it is marked as project-scoped
    // context.
    assertEquals(OnDemandCodeContextTools.PROJECT_SCOPE_HEADER + "\n" + match, output);
  }

  @Test
  public void treeFiltersToChangedFiles() throws Exception {
    when(gitRepoFiles.getPatchSetFileTree(config, change, null))
        .thenReturn(List.of("changed.py", "pre_existing.py"));
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));

    String output = tools.execute("tree", "{}");

    assertEquals("changed.py", output);
  }

  @Test
  public void getContentMarksPreexistingFiles() throws Exception {
    String content = readTestFile(CONTEXT_FILE);
    when(gitRepoFiles.getPatchSetFileContent(change, "context.py")).thenReturn(content);
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));

    String output = tools.execute("get_content", "{\"file_path\":\"context.py\"}");

    assertTrue(output.startsWith("NOTE: This file is pre-existing repository context"));
    assertTrue(output.endsWith(content));
  }

  @Test
  public void grepFiltersToChangedFiles() throws Exception {
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));
    when(gitRepoFiles.grepPatchSet(config, change, "typing", Set.of("changed.py")))
        .thenReturn(List.of("changed.py:1: match"));

    String output = tools.execute("grep", "{\"string\":\"typing\"}");

    assertEquals("changed.py:1: match", output);
  }

  @Test
  public void grepWithExplicitChangeScopeFiltersToChangedFiles() throws Exception {
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));
    when(gitRepoFiles.grepPatchSet(config, change, "typing", Set.of("changed.py")))
        .thenReturn(List.of("changed.py:1: match"));

    String output = tools.execute("grep", "{\"string\":\"typing\",\"scope\":\"change\"}");

    assertEquals("changed.py:1: match", output);
  }

  @Test
  public void grepWithProjectScopeSearchesWholeProject() throws Exception {
    when(gitRepoFiles.grepPatchSet(config, change, "typing", null))
        .thenReturn(List.of("pre_existing.py:7: match"));

    String output = tools.execute("grep", "{\"string\":\"typing\",\"scope\":\"project\"}");

    assertEquals(
        OnDemandCodeContextTools.PROJECT_SCOPE_HEADER + "\npre_existing.py:7: match", output);
    verify(gitRepoFiles, never()).getPatchSetChangedFiles(change);
  }

  @Test
  public void grepWithUnknownScopeFallsBackToChangedFiles() throws Exception {
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));
    when(gitRepoFiles.grepPatchSet(config, change, "typing", Set.of("changed.py")))
        .thenReturn(List.of("changed.py:1: match"));

    String output = tools.execute("grep", "{\"string\":\"typing\",\"scope\":\"repository\"}");

    assertEquals("changed.py:1: match", output);
  }

  @Test
  public void grepWithoutMatchesInChangeScopeSuggestsProjectScope() throws Exception {
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));
    when(gitRepoFiles.grepPatchSet(config, change, "missing", Set.of("changed.py")))
        .thenReturn(List.of());

    String output = tools.execute("grep", "{\"string\":\"missing\"}");

    assertTrue(output.contains("NO MATCH IN CHANGED FILES"));
    assertTrue(output.contains("scope=\"project\""));
    assertFalse(output.contains("CONTEXT NOT PROVIDED"));
  }

  @Test
  public void grepWithoutMatchesInProjectScopeReportsAbsence() throws Exception {
    when(gitRepoFiles.grepPatchSet(config, change, "missing", null)).thenReturn(List.of());

    String output = tools.execute("grep", "{\"string\":\"missing\",\"scope\":\"project\"}");

    assertEquals(
        "NO MATCH IN PROJECT: \"missing\" does not occur in the repository at this patch set.",
        output);
  }

  @Test
  public void grepTruncatesLongResults() throws Exception {
    List<String> matches =
        IntStream.range(0, OnDemandCodeContextTools.MAX_GREP_MATCHES + 50)
            .mapToObj(index -> "changed.py:" + index + ": match")
            .toList();
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));
    when(gitRepoFiles.grepPatchSet(config, change, "match", Set.of("changed.py")))
        .thenReturn(matches);

    String output = tools.execute("grep", "{\"string\":\"match\"}");

    String[] lines = output.split("\n");
    assertEquals(OnDemandCodeContextTools.MAX_GREP_MATCHES + 1, lines.length);
    assertEquals("changed.py:0: match", lines[0]);
    assertEquals("changed.py:199: match", lines[OnDemandCodeContextTools.MAX_GREP_MATCHES - 1]);
    assertTrue(lines[OnDemandCodeContextTools.MAX_GREP_MATCHES].contains("showing 200 of 250"));
  }

  @Test
  public void grepDoesNotTruncateAtCapBoundary() throws Exception {
    List<String> matches =
        IntStream.range(0, OnDemandCodeContextTools.MAX_GREP_MATCHES)
            .mapToObj(index -> "changed.py:" + index + ": match")
            .toList();
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));
    when(gitRepoFiles.grepPatchSet(config, change, "match", Set.of("changed.py")))
        .thenReturn(matches);

    String output = tools.execute("grep", "{\"string\":\"match\"}");

    assertEquals(OnDemandCodeContextTools.MAX_GREP_MATCHES, output.split("\n").length);
    assertFalse(output.contains("truncated"));
  }

  @Test
  public void treeWithProjectScopeSkipsChangedFileFilter() throws Exception {
    when(gitRepoFiles.getPatchSetFileTree(config, change, null))
        .thenReturn(List.of("changed.py", "pre_existing.py"));

    String output = tools.execute("tree", "{\"scope\":\"project\"}");

    assertEquals(
        OnDemandCodeContextTools.PROJECT_SCOPE_HEADER + "\nchanged.py\npre_existing.py", output);
  }

  @Test
  public void treeWithoutMatchingChangedFilesSuggestsProjectScope() throws Exception {
    when(gitRepoFiles.getPatchSetFileTree(config, change, null))
        .thenReturn(List.of("pre_existing.py"));
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("changed.py"));

    String output = tools.execute("tree", "{}");

    assertTrue(output.contains("NO CHANGED FILES IN SCOPE"));
    assertTrue(output.contains("scope=\"project\""));
  }

  @Test
  public void grepPreservesColonInChangedFilePath() throws Exception {
    String match = "schemas/v1:beta.py:1: match";
    when(gitRepoFiles.getPatchSetChangedFiles(change)).thenReturn(Set.of("schemas/v1:beta.py"));
    when(gitRepoFiles.grepPatchSet(config, change, "typing", Set.of("schemas/v1:beta.py")))
        .thenReturn(List.of(match));

    String output = tools.execute("grep", "{\"string\":\"typing\"}");

    assertEquals(match, output);
  }

  @Test
  public void unsupportedToolReturnsEmptyOutput() {
    assertEquals("", tools.execute("get_context", "{}"));
  }

  private String readTestFile(String filename) throws Exception {
    return Files.readString(BASE_PATH.resolve(filename));
  }

  private List<String> readTestFileLines(String filename) throws Exception {
    return Files.readAllLines(BASE_PATH.resolve(filename));
  }
}
