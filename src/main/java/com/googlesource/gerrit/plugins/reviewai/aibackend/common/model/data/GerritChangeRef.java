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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data;

/** Stable identity of a Gerrit change, independent of project or branch moves. */
public record GerritChangeRef(String instanceId, int changeNumber) {
  public GerritChangeRef {
    instanceId = normalize(instanceId);
    if (changeNumber <= 0) {
      throw new IllegalArgumentException("changeNumber must be positive");
    }
  }

  @Override
  public String toString() {
    return instanceId.isEmpty() ? String.valueOf(changeNumber) : instanceId + ":" + changeNumber;
  }

  private static String normalize(String value) {
    return value == null || value.isBlank() ? "" : value;
  }
}
