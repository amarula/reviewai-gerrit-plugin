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

package com.googlesource.gerrit.plugins.reviewai.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class FileUtilsTest {
  @Test
  public void matchesByExtension() {
    assertTrue(FileUtils.matchesExtensionList("src/main.py", List.of("py", "java")));
  }

  @Test
  public void matchesExtensionlessFileByBasename() {
    assertTrue(FileUtils.matchesExtensionList("Jenkinsfile", List.of("Jenkinsfile")));
  }

  @Test
  public void matchesExtensionlessFileInNestedDirectory() {
    assertTrue(FileUtils.matchesExtensionList("ci/Jenkinsfile", List.of("Jenkinsfile")));
  }

  @Test
  public void matchesExtensionlessFileWithLeadingSlash() {
    assertTrue(FileUtils.matchesExtensionList("/Jenkinsfile", List.of("Jenkinsfile")));
  }

  @Test
  public void matchesDotfileByDotStrippedBasename() {
    // ".gitignore" is normalized to "gitignore" by the config parser.
    assertTrue(FileUtils.matchesExtensionList(".gitignore", List.of("gitignore")));
  }

  @Test
  public void matchesDotfileInNestedDirectory() {
    assertTrue(FileUtils.matchesExtensionList(".github/.gitignore", List.of("gitignore")));
  }

  @Test
  public void doesNotMatchExtensionlessFileWhenOnlyExtensionsConfigured() {
    assertFalse(FileUtils.matchesExtensionList("Jenkinsfile", List.of("py", "java")));
  }
}
