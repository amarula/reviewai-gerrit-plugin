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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug;

import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.googlesource.gerrit.plugins.reviewai.utils.RegexUtils.joinAlternation;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.CODE_DELIMITER;

public class DebugCodeBlocksCleaner {
  private final Pattern debugMessagePattern;

  public DebugCodeBlocksCleaner(Localizer localizer) {
    // Gather all possible message dump titles and combine them into a single Regex string
    String openingTitlesRegex =
        joinAlternation(localizer.filterProperties("message.dump.", ".title"));
    debugMessagePattern =
        Pattern.compile(
            "\\s+"
                + CODE_DELIMITER
                + "\\s*("
                + openingTitlesRegex
                + ").*"
                + CODE_DELIMITER
                + "\\s*",
            Pattern.DOTALL);
  }

  public String removeDebugCodeBlocks(String message) {
    Matcher debugMessagematcher = debugMessagePattern.matcher(message);
    return debugMessagematcher.replaceAll("");
  }
}
