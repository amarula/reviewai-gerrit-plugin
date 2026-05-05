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

package com.googlesource.gerrit.plugins.reviewai.aibackend.mock;

import com.google.gerrit.server.config.SitePath;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai.AiClientBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;

@Slf4j
public class MockAiClient extends AiClientBase implements IAiClient {
  private static final String SITE_REVIEWAI_CONFIG_DIR = "etc/reviewai";
  private static final String CLASSPATH_CONFIG_DIR = "config";

  @Getter private volatile String requestBody;
  private final Path sitePath;

  @Inject
  public MockAiClient(Configuration config, @SitePath Path sitePath) {
    super(config);
    this.sitePath = sitePath;
  }

  public MockAiClient(Configuration config) {
    this(config, Path.of(""));
  }

  @Override
  public AiResponseContent ask(ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws Exception {
    String configPath = config.getMockAiModelConfigPath();
    log.debug(
        "Mock AI request received. model={}, behaviorFile={}, change={}, patchSetChars={}",
        config.getAiModel(),
        configPath,
        getChangeId(change),
        patchSet == null ? 0 : patchSet.length());
    log.debug("Mock AI request changeSetData={}", changeSetData);
    log.debug("Mock AI request patchSet={}", patchSet);

    LoadedBehavior loadedBehavior = loadBehavior(configPath);
    MockAiModelBehavior behavior = loadedBehavior.behavior();
    if (behavior == null) {
      throw new IOException("Mock AI model behavior file is empty or invalid");
    }
    requestBody = getGson().toJson(behavior);
    log.debug("Mock AI behavior loaded from {}: {}", loadedBehavior.location(), requestBody);
    long delayMs = behavior.getResolvedDelayMs();
    if (delayMs > 0) {
      log.debug("Delaying mocked AI response by {} ms", delayMs);
      Thread.sleep(delayMs);
    }
    AiResponseContent responseContent = behavior.toResponseContent();
    log.debug("Mock AI response: {}", getGson().toJson(responseContent));
    return responseContent;
  }

  private String getChangeId(GerritChange change) {
    if (change == null) {
      return "";
    }
    try {
      return change.getFullChangeId();
    } catch (RuntimeException e) {
      return change.toString();
    }
  }

  private LoadedBehavior loadBehavior(String configPath) throws IOException {
    if (configPath == null || configPath.isBlank()) {
      throw new IOException("mockAiModelConfigPath is empty");
    }
    String normalizedConfigPath = stripClasspathConfigPrefix(configPath);
    Path siteConfigPath =
        sitePath.resolve(SITE_REVIEWAI_CONFIG_DIR).resolve(normalizedConfigPath);
    if (Files.exists(siteConfigPath)) {
      return loadFromFile(siteConfigPath);
    }

    String classpathConfigPath = CLASSPATH_CONFIG_DIR + "/" + normalizedConfigPath;
    InputStream resource =
        MockAiClient.class.getClassLoader().getResourceAsStream(classpathConfigPath);
    if (resource == null) {
      throw new IOException(
          String.format(
              "Mock AI model behavior file not found: %s. Looked in %s and classpath %s",
              configPath, siteConfigPath, classpathConfigPath));
    }
    try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
      return new LoadedBehavior(
          classpathConfigPath, getGson().fromJson(reader, MockAiModelBehavior.class));
    }
  }

  private LoadedBehavior loadFromFile(Path path) throws IOException {
    try (InputStreamReader reader =
        new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)) {
      return new LoadedBehavior(
          path.toString(), getGson().fromJson(reader, MockAiModelBehavior.class));
    }
  }

  private String stripClasspathConfigPrefix(String configPath) {
    String normalized = configPath.trim();
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return normalized.startsWith(CLASSPATH_CONFIG_DIR + "/")
        ? normalized.substring((CLASSPATH_CONFIG_DIR + "/").length())
        : normalized;
  }

  private record LoadedBehavior(String location, MockAiModelBehavior behavior) {}
}
