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

package com.googlesource.gerrit.plugins.reviewai.avatar;

import com.google.gerrit.server.config.SitePaths;
import com.google.gerrit.server.plugins.Plugin;
import com.google.gerrit.server.plugins.PluginLoader;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Detects whether the avatars-gravatar plugin is installed or already loaded. */
@Singleton
public class ReviewAiAvatarPluginDetector {
  static final String AVATARS_GRAVATAR_PLUGIN_NAME = "avatars-gravatar";

  private final PluginLoader pluginLoader;
  private final SitePaths sitePaths;

  @Inject
  ReviewAiAvatarPluginDetector(PluginLoader pluginLoader, SitePaths sitePaths) {
    this.pluginLoader = pluginLoader;
    this.sitePaths = sitePaths;
  }

  /** Returns true when avatars-gravatar is loaded or present as an enabled plugin file. */
  public boolean isAvatarsGravatarAvailable() {
    Plugin plugin = pluginLoader.get(AVATARS_GRAVATAR_PLUGIN_NAME);
    if (plugin != null && !plugin.isDisabled()) {
      return true;
    }
    return isEnabledPluginFilePresent();
  }

  private boolean isEnabledPluginFilePresent() {
    if (sitePaths.plugins_dir == null || !Files.isDirectory(sitePaths.plugins_dir)) {
      return false;
    }
    try (Stream<Path> plugins = Files.list(sitePaths.plugins_dir)) {
      return plugins
          .filter(Files::isRegularFile)
          .filter(path -> {
            Path namePath = path.getFileName();
            return namePath != null && !namePath.toString().endsWith(".disabled");
          })
          .anyMatch(this::isAvatarsGravatarPlugin);
    } catch (IOException e) {
      return false;
    }
  }

  private boolean isAvatarsGravatarPlugin(Path pluginPath) {
    try {
      return AVATARS_GRAVATAR_PLUGIN_NAME.equals(pluginLoader.getPluginName(pluginPath));
    } catch (RuntimeException e) {
      return false;
    }
  }
}
