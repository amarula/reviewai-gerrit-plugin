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

package com.googlesource.gerrit.plugins.reviewai.config;

import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * A label requirement that must be satisfied before an AI review can start.
 *
 * <p>Parsed from configuration values like {@code "Verified>=1"}.
 * The effective vote for a label is the maximum vote across all voters
 * (matching Gerrit's submit-requirement semantics).
 *
 * <p>Multiple requirements are combined with AND logic: all must be satisfied.
 */
@Slf4j
public final class LabelRequirement {

  @Getter private final String labelName;
  @Getter private final int threshold;

  private LabelRequirement(String labelName, int threshold) {
    this.labelName = labelName;
    this.threshold = threshold;
  }

  /**
   * Parses a config value of the form {@code "Verified>=1"} into a {@link LabelRequirement}.
   *
   * @param configValue the raw configuration string, e.g. "Verified>=1"
   * @return the parsed requirement
   * @throws IllegalArgumentException if the value cannot be parsed
   */
  public static LabelRequirement parse(String configValue) {
    if (configValue == null || configValue.isBlank()) {
      throw new IllegalArgumentException("Label requirement must not be blank");
    }

    String[] parts = configValue.split(">=", 2);
    if (parts.length != 2) {
      throw new IllegalArgumentException(
          "Invalid label requirement format: '"
              + configValue
              + "'. Expected format: <LabelName>>=<Value>, e.g. 'Verified>=1'");
    }

    String labelName = parts[0].trim();
    if (labelName.isEmpty()) {
      throw new IllegalArgumentException(
          "Label name must not be empty: '" + configValue + "'");
    }

    int threshold;
    try {
      threshold = Integer.parseInt(parts[1].trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "Label threshold must be an integer: '" + configValue + "'");
    }

    return new LabelRequirement(labelName, threshold);
  }

  /**
   * Returns true if this requirement is satisfied by the given label votes.
   *
   * <p>The effective vote is the maximum value across all voters for this label.
   * If the label has no votes, the effective value is 0.
   *
   * @param currentApprovals map of label name to maximum vote value
   * @return true if the requirement is met
   */
  public boolean isSatisfiedBy(Map<String, Short> currentApprovals) {
    Short maxVote = currentApprovals.get(labelName);
    int effectiveValue = maxVote != null ? maxVote.intValue() : 0;
    return effectiveValue >= threshold;
  }

  /**
   * Returns true if all requirements are satisfied by the current approvals.
   *
   * @param requirements the list of label requirements
   * @param approvals map of label name to max vote
   * @return true if all requirements are met (or the list is empty)
   */
  public static boolean areSatisfied(
      List<LabelRequirement> requirements, Map<String, Short> approvals) {
    if (requirements.isEmpty()) {
      return true;
    }
    if (approvals == null || approvals.isEmpty()) {
      log.debug("No approvals present, required labels: {}", requirements);
      return false;
    }
    return requirements.stream().allMatch(r -> r.isSatisfiedBy(approvals));
  }

  @Override
  public String toString() {
    return labelName + ">=" + threshold;
  }
}
