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

package com.googlesource.gerrit.plugins.reviewai.avatar;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.plugins.Plugin;
import com.google.gerrit.server.plugins.PluginLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ReviewAiAvatarPluginDetectorTest {
  @Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Test
  public void detectsLoadedGravatarPlugin() throws Exception {
    PluginLoader pluginLoader = mock(PluginLoader.class);
    Plugin plugin = mock(Plugin.class);
    when(plugin.isDisabled()).thenReturn(false);
    when(pluginLoader.get(ReviewAiAvatarPluginDetector.AVATARS_GRAVATAR_PLUGIN_NAME))
        .thenReturn(plugin);

    assertTrue(newDetector(pluginLoader).isAvatarsGravatarAvailable());
  }

  @Test
  public void detectsInstalledGravatarPluginFile() throws Exception {
    PluginLoader pluginLoader = mock(PluginLoader.class);
    SitePaths sitePaths = newSitePaths();
    Path pluginPath = sitePaths.plugins_dir.resolve("avatars-gravatar.jar");
    Files.writeString(pluginPath, "");
    when(pluginLoader.getPluginName(pluginPath))
        .thenReturn(ReviewAiAvatarPluginDetector.AVATARS_GRAVATAR_PLUGIN_NAME);

    assertTrue(
        new ReviewAiAvatarPluginDetector(pluginLoader, sitePaths).isAvatarsGravatarAvailable());
  }

  @Test
  public void ignoresDisabledGravatarPluginFile() throws Exception {
    PluginLoader pluginLoader = mock(PluginLoader.class);
    SitePaths sitePaths = newSitePaths();
    Path pluginPath = sitePaths.plugins_dir.resolve("avatars-gravatar.jar.disabled");
    Files.writeString(pluginPath, "");

    assertFalse(
        new ReviewAiAvatarPluginDetector(pluginLoader, sitePaths).isAvatarsGravatarAvailable());
  }

  @Test
  public void returnsFalseWhenGravatarPluginIsMissing() throws Exception {
    assertFalse(newDetector(mock(PluginLoader.class)).isAvatarsGravatarAvailable());
  }

  private ReviewAiAvatarPluginDetector newDetector(PluginLoader pluginLoader) throws IOException {
    return new ReviewAiAvatarPluginDetector(pluginLoader, newSitePaths());
  }

  private SitePaths newSitePaths() throws IOException {
    SitePaths sitePaths = new SitePaths(temporaryFolder.newFolder().toPath());
    Files.createDirectories(sitePaths.plugins_dir);
    return sitePaths;
  }
}
