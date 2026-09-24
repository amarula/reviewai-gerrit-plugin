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
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Formats condition-label context for inclusion in an AI prompt. */
public final class AiPromptConditionLabelFormatter {
  private static final Map<String, String> DEFAULT_DESCRIPTION_KEYS =
      Map.of("Verified", "prompt.condition.label.verified.description");

  private AiPromptConditionLabelFormatter() {}

  public static String format(
      Map<String, GerritConditionLabel> labels, Function<String, String> localize) {
    if (labels == null || labels.isEmpty()) {
      return "";
    }
    return labels.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(entry -> formatLabel(entry, localize))
        .collect(Collectors.joining("\n"));
  }

  private static String formatLabel(
      Map.Entry<String, GerritConditionLabel> entry, Function<String, String> localize) {
    GerritConditionLabel label = entry.getValue();
    String currentValues = formatValues(label.currentValues(), "no vote");
    String possibleValues = formatValues(label.possibleValues(), "unknown");
    String description =
        label.description() == null || label.description().isBlank()
            ? getDefaultDescription(entry.getKey(), localize)
            : label.description();
    String formattedLabel =
        entry.getKey()
            + ":\n  Current value: "
            + currentValues
            + "\n  Possible values: "
            + possibleValues;
    if (description.isBlank()) {
      return formattedLabel;
    }
    return formattedLabel + "\n  Description: " + description;
  }

  private static String formatValues(List<Short> values, String emptyValue) {
    return values.isEmpty()
        ? emptyValue
        : values.stream()
            .map(value -> (value >= 0 ? "+" : "") + value)
            .collect(Collectors.joining(", "));
  }

  private static String getDefaultDescription(String labelName, Function<String, String> localize) {
    String descriptionKey = DEFAULT_DESCRIPTION_KEYS.get(labelName);
    return descriptionKey == null ? "" : localize.apply(descriptionKey);
  }
}
