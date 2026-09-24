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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit;

import java.util.List;

/** Current and possible values plus the description of a Gerrit review-condition label. */
public record GerritConditionLabel(
    List<Short> currentValues, List<Short> possibleValues, String description) {
  public GerritConditionLabel {
    currentValues = List.copyOf(currentValues);
    possibleValues = List.copyOf(possibleValues);
  }

  public GerritConditionLabel(List<Short> currentValues, String description) {
    this(currentValues, List.of(), description);
  }
}
