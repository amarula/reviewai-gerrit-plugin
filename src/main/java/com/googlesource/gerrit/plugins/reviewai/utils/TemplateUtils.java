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

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
public class TemplateUtils extends StringUtils {
  /**
   * TemplateUtils is a minimal utility class to provide additional functionality for rendering text
   * templates. It is specifically designed to handle the substitution of placeholders within a
   * template string with values provided in a map. This class is tailored for scenarios requiring
   * dynamic text generation where placeholders in the template may include optional spaces and are
   * wrapped in curly braces and percentage signs * (e.g., "{% key %}").
   */
  public static String renderTemplate(String template, Map<String, String> values) {
    /**
     * Replaces placeholders in the provided template string with the corresponding values from the
     * map. Placeholders are identified even if they include spaces around the key names, enhancing
     * the method's robustness against format variations. The method ensures that double quotes in
     * the replacement values are escaped to prevent breaking string delimiters in generated code or
     * outputs.
     */
    log.debug("Starting template rendering");
    String result = template;
    for (Map.Entry<String, String> entry : values.entrySet()) {
      String placeholder = "\\{%\\s*" + entry.getKey() + "\\s*%\\}";
      String value = doubleBackslashDoubleQuotes(entry.getValue());
      result = result.replaceAll(placeholder, value);
      log.debug("Replaced '{}' with '{}'", placeholder, value);
    }
    log.debug("Completed rendering template");
    return result;
  }
}
