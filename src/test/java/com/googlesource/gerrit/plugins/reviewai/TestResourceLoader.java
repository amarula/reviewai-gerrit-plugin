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

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Resolves test resource paths under Bazel and IDE/Maven environments.
 *
 * <p>Under {@code bazel test} the sandbox working directory is the execroot, so relative paths like
 * {@code src/test/resources/...} do not exist. The BUILD file passes the package-relative resource
 * path via {@code -Dbazel.test.resourceBase=...}, which is combined with {@code TEST_SRCDIR} to
 * locate the source tree under the runfiles.
 *
 * <p>Outside Bazel (IDE, Maven), the system property is absent so we fall back to the standard
 * relative path.
 */
public final class TestResourceLoader {
  private static final String BAZEL_BASE_PROPERTY = "bazel.test.resourceBase";
  private static final String BAZEL_TEST_SRCDIR = "TEST_SRCDIR";
  private static final String WORKSPACE_NAME = "_main";
  private static final Path FALLBACK_BASE = Paths.get("src/test/resources");

  private static volatile Path basePath;

  private TestResourceLoader() {}

  /** Returns the base path to test resources, resolved once per JVM. */
  public static Path getTestResourcePath() {
    if (basePath != null) {
      return basePath;
    }
    String bazelBase = System.getProperty(BAZEL_BASE_PROPERTY);
    String srcdir = System.getenv(BAZEL_TEST_SRCDIR);
    if (bazelBase != null && srcdir != null) {
      basePath = Paths.get(srcdir, WORKSPACE_NAME, bazelBase);
    } else {
      basePath = FALLBACK_BASE;
    }
    return basePath;
  }
}
