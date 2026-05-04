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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.ondemand;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class OnDemandCodeContextToolsTest extends TestBase {
  private static final Path BASE_PATH = Path.of("src/test/resources");
  private static final String CONTEXT_FILE = "__files/openai/contextPatchOriginal.py";

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
  public void treeReturnsRepositoryPathsFromSubdir() {
    when(gitRepoFiles.getFileTree(config, change, "src"))
        .thenReturn(List.of("src/Main.java", "src/util/Helper.java"));

    String output = tools.execute("tree", "{\"subdir\":\"src\"}");

    assertEquals("src/Main.java\nsrc/util/Helper.java", output);
  }

  @Test
  public void getContentReturnsFileContentFromProjectRoot() throws Exception {
    String content = readTestFile(CONTEXT_FILE);
    when(gitRepoFiles.getFileContent(change, "context.py")).thenReturn(content);

    String output = tools.execute("get_content", "{\"file_path\":\"context.py\"}");

    assertEquals(content, output);
  }

  @Test
  public void grepReturnsMatches() throws Exception {
    String firstLine = readTestFile(CONTEXT_FILE).split("\\R", 2)[0];
    String match = "context.py:1: " + firstLine;
    when(gitRepoFiles.grep(config, change, "typing")).thenReturn(List.of(match));

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
}
