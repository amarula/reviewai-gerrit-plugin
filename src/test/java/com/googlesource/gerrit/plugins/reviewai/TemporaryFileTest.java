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

package com.googlesource.gerrit.plugins.reviewai;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.googlesource.gerrit.plugins.reviewai.utils.FileUtils;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class TemporaryFileTest {
  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void testCreateTempFileWithContent() throws IOException {
    // Prepare
    String prefix = "testFile";
    String suffix = ".txt";
    String content = "This is a test content";

    // Execute
    Path tempFile = FileUtils.createTempFileWithContent(prefix, suffix, content);

    // Verify the file exists
    assertTrue("Temporary file should exist", Files.exists(tempFile));

    // Read back the content
    String fileContent = Files.readString(tempFile);
    assertEquals("File content should match", content, fileContent);
  }
}
