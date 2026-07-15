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
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

public class TreeOutputCompressorTest {
  private static final Path BASE_PATH = Path.of("src/test/resources");
  private static final String SMALL_TREE_FILE = "__files/ondemand/treeSmall.txt";
  private static final String LARGE_TREE_FILE = "__files/ondemand/treeLarge.txt";

  @Test
  public void formatReturnsFullTreeWhenBelowLimit() throws Exception {
    List<String> paths = readTestFileLines(SMALL_TREE_FILE);

    String output = new TreeOutputCompressor(1_000).format(paths, "src");

    assertEquals(String.join("\n", paths), output);
  }

  @Test
  public void formatEllipsesDirectoriesWhenTreeExceedsLimit() throws Exception {
    String output = new TreeOutputCompressor(40).format(readTestFileLines(LARGE_TREE_FILE), "src");

    assertEquals("src/main/...\nsrc/test/...", output);
    assertTrue(output.length() <= 40);
  }

  @Test
  public void formatExpandsRequestedEllipsedDirectoryOneLevelDeeper() throws Exception {
    String output =
        new TreeOutputCompressor(40).format(readTestFileLines(LARGE_TREE_FILE), "src/main");

    assertEquals("src/main/java/...", output);
    assertTrue(output.length() <= 40);
  }

  private List<String> readTestFileLines(String filename) throws Exception {
    return Files.readAllLines(BASE_PATH.resolve(filename));
  }
}
