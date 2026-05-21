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

package com.googlesource.gerrit.plugins.reviewai.config.dynamic;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.DynamicDirectivesModifyException;
import lombok.Getter;

import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.jsonArrayToList;

public class DynamicConfigManagerDirectives extends DynamicConfigManager {
  @Getter private final List<String> directives;

  public DynamicConfigManagerDirectives(PluginDataHandlerProvider pluginDataHandlerProvider) {
    super(pluginDataHandlerProvider);
    directives = jsonArrayToList(getConfig(Configuration.KEY_DIRECTIVES));
  }

  public void addDirective(String directive) {
    directives.add(directive);
    updateConfiguration();
  }

  public void removeDirective(String directiveIndex) throws DynamicDirectivesModifyException {
    try {
      int index = Integer.parseInt(directiveIndex) - 1;
      directives.remove(index);
    } catch (IndexOutOfBoundsException | NumberFormatException e) {
      throw new DynamicDirectivesModifyException(e.getMessage());
    }
    updateConfiguration();
  }

  public void resetDirectives() {
    directives.clear();
    setConfig(Configuration.KEY_DIRECTIVES, "");
    updateConfiguration(true, true);
  }

  private void updateConfiguration() {
    setConfig(Configuration.KEY_DIRECTIVES, getGson().toJson(directives));
    updateConfiguration(true, false);
  }
}
