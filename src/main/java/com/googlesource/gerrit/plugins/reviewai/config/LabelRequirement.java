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

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * A label requirement that must be satisfied before an AI review can start.
 *
 * <p>Parsed from configuration values like {@code "Verified>=1"} or {@code "Code-Review=MAX"}.
 * The label name and minimum value are extracted, and the requirement is considered satisfied
 * when the maximum vote for the label across all voters meets or exceeds the configured threshold.
 */
@Slf4j
public final class LabelRequirement {
  private static final Pattern PARSE_PATTERN =
      Pattern.compile("^\\s*(.+?)\\s*(>=|>|=)\\s*(.+?)\\s*$");

  @Getter private final String labelName;
  @Getter private final Operator operator;
  @Getter private final int threshold;
  @Getter private final String rawValue; // kept for MAX/MIN resolution

  /** Comparison operators supported in label requirement expressions. */
  public enum Operator {
    GREATER_OR_EQUAL(">="),
    GREATER_THAN(">"),
    EQUAL("=");

    private final String symbol;

    Operator(String symbol) {
      this.symbol = symbol;
    }

    public String getSymbol() {
      return symbol;
    }

    static Operator fromSymbol(String symbol) {
      return Arrays.stream(values())
          .filter(op -> op.symbol.equals(symbol))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("Unknown operator: " + symbol));
    }
  }

  /** Strategy for combining multiple label requirements. */
  public enum Strategy {
    /** All requirements must be satisfied. */
    ALL,
    /** At least one requirement must be satisfied. */
    ANY
  }

  /** Special value tokens that can appear in place of a numeric threshold. */
  public static final String MAX_TOKEN = "MAX";
  public static final String MIN_TOKEN = "MIN";

  /** Sentinel used when the threshold is MAX or MIN and must be resolved against label config. */
  public static final int UNRESOLVED_MAX_SENTINEL = Integer.MAX_VALUE;
  public static final int UNRESOLVED_MIN_SENTINEL = Integer.MIN_VALUE;

  private LabelRequirement(String labelName, Operator operator, int threshold, String rawValue) {
    this.labelName = labelName;
    this.operator = operator;
    this.threshold = threshold;
    this.rawValue = rawValue;
  }

  /**
   * Parses a config value of the form {@code "Verified>=1"} into a {@link LabelRequirement}.
   *
   * <p>Supports:
   * <ul>
   *   <li>{@code Verified>=1} — at least +1</li>
   *   <li>{@code Code-Review=MAX} — the maximum possible vote</li>
   *   <li>{@code Verified=MIN} — the minimum possible vote</li>
   * </ul>
   *
   * @param configValue the raw configuration string
   * @return the parsed requirement
   * @throws IllegalArgumentException if the value cannot be parsed
   */
  public static LabelRequirement parse(String configValue) {
    if (configValue == null || configValue.isBlank()) {
      throw new IllegalArgumentException("Label requirement must not be blank");
    }

    Matcher matcher = PARSE_PATTERN.matcher(configValue);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "Invalid label requirement format: '"
              + configValue
              + "'. Expected format: <LabelName><operator><Value>, e.g. 'Verified>=1'");
    }

    String labelName = matcher.group(1).trim();
    Operator operator = Operator.fromSymbol(matcher.group(2));
    String valueStr = matcher.group(3).trim();

    int threshold;
    if (MAX_TOKEN.equalsIgnoreCase(valueStr)) {
      threshold = UNRESOLVED_MAX_SENTINEL;
    } else if (MIN_TOKEN.equalsIgnoreCase(valueStr)) {
      threshold = UNRESOLVED_MIN_SENTINEL;
    } else {
      try {
        threshold = Integer.parseInt(valueStr);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Invalid label value '"
                + valueStr
                + "'. Must be an integer, MAX, or MIN. "
                + "Full entry: '"
                + configValue
                + "'");
      }
    }

    return new LabelRequirement(labelName, operator, threshold, valueStr);
  }

  /**
   * Parses a comma-separated config value (from dynamic config) into a list of requirements.
   *
   * @param configValue comma-separated label requirements, e.g. {@code "Verified>=1, Code-Review>=1"}
   * @return list of parsed requirements
   */
  public static List<LabelRequirement> parseList(String configValue) {
    if (configValue == null || configValue.isBlank()) {
      return List.of();
    }
    return Arrays.stream(configValue.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .map(LabelRequirement::parse)
        .toList();
  }

  /**
   * Returns true if this requirement is satisfied by the given label votes.
   *
   * <p>Uses the maximum vote across all voters for this label. If the label has no votes,
   * the effective value is 0.
   *
   * @param currentApprovals map of label name to maximum vote value
   * @return true if the requirement is met
   */
  public boolean isSatisfiedBy(Map<String, Short> currentApprovals) {
    Short maxVote = currentApprovals.get(labelName);
    int effectiveValue = maxVote != null ? maxVote.intValue() : 0;

    return switch (operator) {
      case GREATER_OR_EQUAL -> effectiveValue >= threshold;
      case GREATER_THAN -> effectiveValue > threshold;
      case EQUAL -> effectiveValue == threshold;
    };
  }

  /**
   * Checks whether the current approvals satisfy all requirements according
   * to the given strategy.
   *
   * @param requirements the list of label requirements
   * @param strategy ALL (every requirement) or ANY (at least one)
   * @param approvals map of label name → max vote
   * @return true if requirements are met
   */
  public static boolean areSatisfied(
      List<LabelRequirement> requirements,
      Strategy strategy,
      Map<String, Short> approvals) {
    if (requirements.isEmpty()) {
      return true;
    }
    if (approvals == null || approvals.isEmpty()) {
      log.debug("No approvals present, required labels: {}", requirements);
      return false;
    }
    return switch (strategy) {
      case ALL -> requirements.stream().allMatch(r -> r.isSatisfiedBy(approvals));
      case ANY -> requirements.stream().anyMatch(r -> r.isSatisfiedBy(approvals));
    };
  }

  @Override
  public String toString() {
    return labelName + operator.getSymbol() + rawValue;
  }
}
