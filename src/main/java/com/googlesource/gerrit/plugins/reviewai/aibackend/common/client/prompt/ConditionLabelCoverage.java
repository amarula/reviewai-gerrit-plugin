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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Derives which review scopes a positive Condition Label vote proves, based on the label
 * description that the Gerrit administrators configure. The derived scopes are what makes a passing
 * CI result conclusive for a review scope: a description that mentions compilation, for instance,
 * means a positive vote settles whether the Patch Set compiles.
 */
public final class ConditionLabelCoverage {
  private static final Map<String, List<Pattern>> SCOPE_PATTERNS = scopePatterns();

  private ConditionLabelCoverage() {}

  /**
   * Returns the review scopes covered by this label, or an empty string when the label is not a
   * conclusive positive vote or its effective description does not name any known scope. The
   * effective description is passed in because a label without one falls back to the localized
   * default.
   */
  public static String describe(GerritConditionLabel label, String effectiveDescription) {
    if (label == null || !isConclusivePositive(label) || effectiveDescription == null) {
      return "";
    }
    String description = effectiveDescription.toLowerCase();
    return SCOPE_PATTERNS.entrySet().stream()
        .filter(
            entry ->
                entry.getValue().stream().anyMatch(pattern -> pattern.matcher(description).find()))
        .map(Map.Entry::getKey)
        .collect(Collectors.joining(", "));
  }

  /**
   * A label is conclusive only when its approvals agree on a positive value: conflicting votes
   * ({@code -1} and {@code +1}) leave the label unresolved, so no scope can be derived from it.
   */
  public static boolean isConclusivePositive(GerritConditionLabel label) {
    if (label == null) {
      return false;
    }
    List<Short> currentValues = label.currentValues();
    return currentValues.stream().anyMatch(value -> value > 0)
        && currentValues.stream().noneMatch(value -> value < 0);
  }

  private static Map<String, List<Pattern>> scopePatterns() {
    Map<String, List<Pattern>> scopes = new LinkedHashMap<>();
    scopes.put("compilation", List.of(pattern("\\bcompil\\w*"), pattern("\\bbuild\\w*")));
    scopes.put(
        "automated tests",
        List.of(
            pattern("\\btests?\\b"), pattern("\\btest suite\\b"), pattern("\\bunit tests?\\b")));
    scopes.put(
        "static analysis",
        List.of(
            pattern("\\blint\\w*"),
            pattern("\\bstatic analysis\\b"),
            pattern("\\bquality gate\\b"),
            pattern("\\bsonar\\w*")));
    scopes.put(
        "security scanning",
        List.of(
            pattern("\\bsecurity scan\\w*"),
            pattern("\\bvulnerabilit\\w*"),
            pattern("\\bcve\\b"),
            pattern("\\bsast\\b")));
    scopes.put("documentation", List.of(pattern("\\bdocumentation\\b"), pattern("\\bjavadoc\\b")));
    return scopes;
  }

  private static Pattern pattern(String expression) {
    return Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
  }
}
