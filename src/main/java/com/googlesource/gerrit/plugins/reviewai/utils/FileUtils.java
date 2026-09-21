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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FileUtils {
  private static final String ALL_FILE_EXTENSIONS = "ALL";

  public static InputStreamReader getInputStreamReader(String filename) {
    InputStream inputStream = FileUtils.class.getClassLoader().getResourceAsStream(filename);
    if (inputStream == null) {
      String message = "File not found or error reading the file: " + filename;
      log.error(message);
      throw new RuntimeException(message);
    }
    InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
    log.debug("Input stream reader created for file: {}", filename);
    return reader;
  }

  public static Path createTempFileWithContent(String prefix, String suffix, String content) {
    Path tempFile;
    try {
      tempFile = Files.createTempFile(prefix, suffix);
      Files.write(tempFile, content.getBytes(StandardCharsets.UTF_8));
      log.debug("Temporary file created: {}", tempFile);
    } catch (IOException e) {
      log.error("Failed to create or write to temporary file: {}", e.getMessage(), e);
      throw new RuntimeException(e);
    }
    tempFile.toFile().deleteOnExit();

    return tempFile;
  }

  public static boolean matchesExtensionList(String filename, List<String> extensions) {
    boolean matches =
        extensions.contains(ALL_FILE_EXTENSIONS)
            || extensions.contains(getExtension(filename))
            || extensions.contains(getBasename(filename))
            || extensions.contains(stripLeadingDot(getBasename(filename)));
    log.debug("Filename '{}' matches extension list: {}", filename, matches);
    return matches;
  }

  public static boolean isFileExtensionEnabled(
      String filename, List<String> enabledExtensions, List<String> disabledExtensions) {
    return matchesExtensionList(filename, enabledExtensions)
        && (disabledExtensions == null || !matchesExtensionList(filename, disabledExtensions));
  }

  public static String sanitizeFilename(String filename) {
    String sanitized = filename.replaceAll("[^-_a-zA-Z0-9]", "+");
    log.debug("Original filename: '{}', Sanitized filename: '{}'", filename, sanitized);
    return sanitized;
  }

  public static String getExtension(String filename) {
    int lastDotIndex = filename.lastIndexOf('.');
    if (lastDotIndex <= 0 || lastDotIndex == filename.length() - 1) {
      return "";
    }
    return filename.substring(lastDotIndex + 1);
  }

  private static String getBasename(String filename) {
    int lastSlashIndex = filename.lastIndexOf('/');
    return lastSlashIndex == -1 ? filename : filename.substring(lastSlashIndex + 1);
  }

  private static String stripLeadingDot(String name) {
    return name.startsWith(".") ? name.substring(1) : name;
  }
}
