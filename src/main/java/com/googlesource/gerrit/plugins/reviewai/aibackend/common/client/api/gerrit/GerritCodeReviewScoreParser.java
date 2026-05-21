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

import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_DEFAULT_MESSAGE_PATCH_SET;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GerritCodeReviewScoreParser {
  private static final Pattern PATCH_SET_HEADER_PATTERN =
      Pattern.compile(
          "^(?:"
              + GERRIT_DEFAULT_MESSAGE_PATCH_SET
              + "|Uploaded patch set) \\d+:[^\\n]*",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern CODE_REVIEW_SCORE_PATTERN =
      Pattern.compile("(?<![-\\w])Code-Review\\s*([+-]\\d+)\\b");

  private GerritCodeReviewScoreParser() {}

  public static String getCodeReviewScore(String message) {
    return Optional.ofNullable(message)
        .map(PATCH_SET_HEADER_PATTERN::matcher)
        .filter(Matcher::find)
        .map(Matcher::group)
        .map(GerritCodeReviewScoreParser::getLastCodeReviewScore)
        .orElse(null);
  }

  private static String getLastCodeReviewScore(String patchSetHeader) {
    Matcher matcher = CODE_REVIEW_SCORE_PATTERN.matcher(patchSetHeader);
    String score = null;
    while (matcher.find()) {
      score = matcher.group(1);
    }
    return score;
  }
}
