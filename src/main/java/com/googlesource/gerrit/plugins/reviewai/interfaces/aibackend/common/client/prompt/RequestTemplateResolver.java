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

package com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt;

/** Selects a request template for a skill, with its bundled template as fallback. */
@FunctionalInterface
public interface RequestTemplateResolver {
  /**
   * Returns the effective request template for {@code skillName}, falling back to {@code
   * bundledTemplate} when no usable customization is available.
   */
  String resolve(String skillName, String bundledTemplate);
}
