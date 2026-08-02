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

import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ResourceUtils {
  private ResourceUtils() {}

  @FunctionalInterface
  public interface ResourceFileConsumer {
    void accept(String resourceName, InputStream inputStream) throws Exception;
  }

  public static void forEachResourceFile(
      ClassLoader classLoader,
      String resourceDirectory,
      String resourceSuffix,
      ResourceFileConsumer consumer)
      throws Exception {
    Enumeration<URL> resources = classLoader.getResources(resourceDirectory);
    while (resources.hasMoreElements()) {
      URL resource = resources.nextElement();
      forEachResourceFile(resource, resourceDirectory, resourceSuffix, consumer);
    }
  }

  private static void forEachResourceFile(
      URL resource,
      String resourceDirectory,
      String resourceSuffix,
      ResourceFileConsumer consumer)
      throws Exception {
    if ("file".equals(resource.getProtocol())) {
      forEachFileResource(resource, resourceSuffix, consumer);
      return;
    }
    if ("jar".equals(resource.getProtocol())) {
      forEachJarResource(resource, resourceDirectory, resourceSuffix, consumer);
    }
  }

  private static void forEachFileResource(
      URL resource, String resourceSuffix, ResourceFileConsumer consumer) throws Exception {
    Path directory = Path.of(resource.toURI());
    try (var paths = Files.list(directory)) {
      for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
        Path namePath = path.getFileName();
        if (namePath == null) {
          continue;
        }
        String filename = namePath.toString();
        if (filename.endsWith(resourceSuffix)) {
          try (InputStream inputStream = Files.newInputStream(path)) {
            consumer.accept(filename, inputStream);
          }
        }
      }
    }
  }

  private static void forEachJarResource(
      URL resource,
      String resourceDirectory,
      String resourceSuffix,
      ResourceFileConsumer consumer)
      throws Exception {
    JarURLConnection connection = (JarURLConnection) resource.openConnection();
    connection.setUseCaches(false);
    try (JarFile jarFile = connection.getJarFile()) {
      List<JarEntry> entries = new ArrayList<>();
      Enumeration<JarEntry> jarEntries = jarFile.entries();
      while (jarEntries.hasMoreElements()) {
        JarEntry entry = jarEntries.nextElement();
        if (!entry.isDirectory()
            && entry.getName().startsWith(resourceDirectory + "/")
            && entry.getName().endsWith(resourceSuffix)) {
          entries.add(entry);
        }
      }
      entries.sort(Comparator.comparing(JarEntry::getName));
      for (JarEntry entry : entries) {
        try (InputStream inputStream = jarFile.getInputStream(entry)) {
          consumer.accept(entry.getName(), inputStream);
        }
      }
    }
  }
}
