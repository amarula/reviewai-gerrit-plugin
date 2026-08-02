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

package com.googlesource.gerrit.plugins.reviewai.localization;

import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

@Slf4j
public class Localizer {
  private final ResourceBundle resourceBundle;

  @Inject
  public Localizer(Configuration config) {
    resourceBundle = ResourceBundle.getBundle("localization.localTexts", config.getLocaleDefault());
    log.debug("ResourceBundle initialized with locale: {}", config.getLocaleDefault());
  }

  public String getText(String key) {
    String text = resourceBundle.getString(key);
    log.debug("Retrieved text for key '{}': {}", key, text);
    return text;
  }

  public List<String> filterProperties(String prefix, String suffix) {
    // Return a list of values of the keys starting with "prefix" and ending with "suffix"
    return resourceBundle.keySet().stream()
        .filter(
            key ->
                (prefix == null || key.startsWith(prefix))
                    && (suffix == null || key.endsWith(suffix)))
        .map(resourceBundle::getString)
        .collect(Collectors.toList());
  }
}
