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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import com.google.gerrit.extensions.common.LabelInfo;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Resolves current Gerrit values for labels referenced by an AI review condition. */
public final class AiReviewConditionLabelResolver {
  private static final Pattern LABEL_EXPRESSION =
      Pattern.compile(
          "(?i)(?:^|[\\s(])-?label:(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|[^\\s()]+)");
  private static final Pattern TRAILING_NUMERIC_VOTE = Pattern.compile("^(.*?)[+-]\\d+$");

  private final Map<String, Map<String, List<Short>>> currentValuesByChange = new HashMap<>();

  boolean hasConditionLabels(String expression) {
    return !extractConditionLabels(expression).isEmpty();
  }

  void cacheCurrentValues(String changeId, Map<String, LabelInfo> labels) {
    currentValuesByChange.put(changeId, toCurrentLabelValues(labels));
  }

  Map<String, List<Short>> resolve(String changeId, String expression) {
    Set<String> conditionLabels = extractConditionLabels(expression);
    if (conditionLabels.isEmpty()) {
      return Map.of();
    }
    Map<String, List<Short>> valuesForChange =
        currentValuesByChange.getOrDefault(changeId, Map.of());
    Map<String, List<Short>> result = new LinkedHashMap<>();
    conditionLabels.forEach(
        label -> {
          Optional<Map.Entry<String, List<Short>>> currentLabel =
              valuesForChange.entrySet().stream()
                  .filter(entry -> entry.getKey().equalsIgnoreCase(label))
                  .findFirst();
          if (currentLabel.isPresent()) {
            result.put(currentLabel.get().getKey(), currentLabel.get().getValue());
          } else {
            result.put(label, List.of());
          }
        });
    return result;
  }

  /** Formats resolved condition-label values for inclusion in an AI prompt. */
  public static String formatConditionLabelValues(Map<String, List<Short>> labelValues) {
    if (labelValues == null || labelValues.isEmpty()) {
      return "";
    }
    return labelValues.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .map(
            entry ->
                "- "
                    + entry.getKey()
                    + ": "
                    + (entry.getValue().isEmpty()
                        ? "no vote"
                        : entry.getValue().stream()
                            .map(value -> (value >= 0 ? "+" : "") + value)
                            .collect(Collectors.joining(", "))))
        .collect(Collectors.joining("\n"));
  }

  private static Set<String> extractConditionLabels(String expression) {
    if (expression == null || expression.isBlank()) {
      return Set.of();
    }
    Set<String> labels = new LinkedHashSet<>();
    Matcher matcher = LABEL_EXPRESSION.matcher(expression);
    while (matcher.find()) {
      String operand = matcher.group(1);
      if ((operand.startsWith("\"") && operand.endsWith("\""))
          || (operand.startsWith("'") && operand.endsWith("'"))) {
        operand = operand.substring(1, operand.length() - 1);
      }
      int argumentSeparator = operand.indexOf(',');
      if (argumentSeparator >= 0) {
        operand = operand.substring(0, argumentSeparator);
      }
      int operatorIndex = firstOperatorIndex(operand);
      if (operatorIndex >= 0) {
        operand = operand.substring(0, operatorIndex);
      } else {
        Matcher numericVote = TRAILING_NUMERIC_VOTE.matcher(operand);
        if (numericVote.matches()) {
          operand = numericVote.group(1);
        }
      }
      if (!operand.isBlank()) {
        labels.add(operand);
      }
    }
    return labels;
  }

  private static int firstOperatorIndex(String operand) {
    for (int i = 0; i < operand.length(); i++) {
      char character = operand.charAt(i);
      if (character == '=' || character == '<' || character == '>') {
        return i;
      }
    }
    return -1;
  }

  private static Map<String, List<Short>> toCurrentLabelValues(
      Map<String, LabelInfo> labels) {
    if (labels == null || labels.isEmpty()) {
      return Map.of();
    }
    Map<String, List<Short>> values = new HashMap<>();
    labels.forEach(
        (labelName, labelInfo) -> {
          Set<Short> distinctValues = new TreeSet<>();
          if (labelInfo != null && labelInfo.all != null) {
            labelInfo.all.stream()
                .map(approval -> approval.value)
                .filter(Objects::nonNull)
                .map(Integer::shortValue)
                .forEach(distinctValues::add);
          }
          values.put(labelName, List.copyOf(distinctValues));
        });
    return values;
  }
}
