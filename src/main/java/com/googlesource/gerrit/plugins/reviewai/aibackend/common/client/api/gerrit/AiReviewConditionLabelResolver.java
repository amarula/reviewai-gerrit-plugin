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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
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

/** Resolves current Gerrit values for labels referenced by an AI review condition. */
public final class AiReviewConditionLabelResolver {
  private static final Pattern LABEL_EXPRESSION =
      Pattern.compile(
          "(?i)(?:^|[\\s(])-?label:(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*'|[^\\s()]+)");
  private static final Pattern TRAILING_NUMERIC_VOTE = Pattern.compile("^(.*?)[+-]\\d+$");

  private final Map<String, Map<String, GerritConditionLabel>> currentLabelsByChange =
      new HashMap<>();

  boolean hasConditionLabels(String expression) {
    return !extractConditionLabels(expression).isEmpty();
  }

  void cacheCurrentLabels(String changeId, Map<String, LabelInfo> labels) {
    currentLabelsByChange.put(changeId, toCurrentLabels(labels));
  }

  Map<String, GerritConditionLabel> resolve(String changeId, String expression) {
    Set<String> conditionLabels = extractConditionLabels(expression);
    if (conditionLabels.isEmpty()) {
      return Map.of();
    }
    Map<String, GerritConditionLabel> labelsForChange =
        currentLabelsByChange.getOrDefault(changeId, Map.of());
    Map<String, GerritConditionLabel> result = new LinkedHashMap<>();
    conditionLabels.forEach(
        label -> {
          Optional<Map.Entry<String, GerritConditionLabel>> currentLabel =
              labelsForChange.entrySet().stream()
                  .filter(entry -> entry.getKey().equalsIgnoreCase(label))
                  .findFirst();
          if (currentLabel.isPresent()) {
            result.put(currentLabel.get().getKey(), currentLabel.get().getValue());
          } else {
            result.put(label, new GerritConditionLabel(List.of(), null));
          }
        });
    return result;
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

  private static Map<String, GerritConditionLabel> toCurrentLabels(Map<String, LabelInfo> labels) {
    if (labels == null || labels.isEmpty()) {
      return Map.of();
    }
    Map<String, GerritConditionLabel> conditionLabels = new HashMap<>();
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
          conditionLabels.put(
              labelName,
              new GerritConditionLabel(
                  List.copyOf(distinctValues), labelInfo == null ? null : labelInfo.description));
        });
    return conditionLabels;
  }
}
