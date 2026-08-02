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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import java.util.Map;
import java.util.stream.Collectors;

/** Formats condition-label context for inclusion in an AI prompt. */
public final class AiPromptConditionLabelFormatter {
  private AiPromptConditionLabelFormatter() {}

  public static String format(Map<String, GerritConditionLabel> labels) {
    if (labels == null || labels.isEmpty()) {
      return "";
    }
    return labels.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(AiPromptConditionLabelFormatter::formatLabel)
        .collect(Collectors.joining("\n"));
  }

  private static final Map<String, String> DEFAULT_DESCRIPTIONS = Map.of(
      "Verified", "Verified label usually means that automated tests have run and the code compiles and passes basic checks"
  );

  private static String formatLabel(Map.Entry<String, GerritConditionLabel> entry) {
    GerritConditionLabel label = entry.getValue();
    String values =
        label.values().isEmpty()
            ? "no vote"
            : label.values().stream()
                .map(value -> (value >= 0 ? "+" : "") + value)
                .collect(Collectors.joining(", "));
    String description =
        label.description() == null || label.description().isBlank()
            ? DEFAULT_DESCRIPTIONS.getOrDefault(entry.getKey(), "")
            : label.description();
    if (description.isBlank()) {
      return "- " + entry.getKey() + ": " + values;
    }
    return "- " + entry.getKey() + ": " + values + "\n  Description: " + description;
  }
}
