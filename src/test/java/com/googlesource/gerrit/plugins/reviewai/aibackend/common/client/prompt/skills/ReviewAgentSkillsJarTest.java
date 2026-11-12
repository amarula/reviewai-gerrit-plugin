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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReviewAgentSkillsJarTest {
  private static final String SKILL_FILE = "skills/code-review/SKILL.md";

  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void loadsSkillFromJarWithoutDirectoryEntries() throws Exception {
    Path jar = createSkillJar();
    try (var classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
      assertEquals(
          ReviewAgentSkills.instructions("code-review"),
          ReviewAgentSkills.load("code-review", classLoader).content());
    }
    // The adapter closes the filesystem it opened, so another load can mount the JAR again.
    try (var classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
      assertEquals(
          ReviewAgentSkills.instructions("code-review"),
          ReviewAgentSkills.load("code-review", classLoader).content());
    }
  }

  @Test
  public void loadsSkillWithoutClosingExistingJarFileSystem() throws Exception {
    Path jar = createSkillJar();
    try (var fileSystem = FileSystems.newFileSystem(jar);
        var classLoader = new URLClassLoader(new URL[] {jar.toUri().toURL()}, null)) {
      assertEquals(
          ReviewAgentSkills.instructions("code-review"),
          ReviewAgentSkills.load("code-review", classLoader).content());
      assertTrue(fileSystem.isOpen());
    }
  }

  private Path createSkillJar() throws Exception {
    Path jar = temporaryFolder.newFile("skills.jar").toPath();
    try (var output = new JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
      output.putNextEntry(new JarEntry(SKILL_FILE));
      output.write(readResource(SKILL_FILE).getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
    return jar;
  }

  private String readResource(String name) throws Exception {
    try (InputStream resource = getClass().getClassLoader().getResourceAsStream(name)) {
      assertNotNull(name, resource);
      return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
