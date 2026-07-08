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

package com.googlesource.gerrit.plugins.reviewai.logging;

import java.util.List;
import java.util.stream.Collectors;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.prettyFormatList;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.*;

public class LoggerFilterDecider {
  private String filterString;
  private List<String[]> filters;

  public LoggerFilterDecider(String filter) {
    if (!filter.isEmpty()) {
      filterString = unwrapDeSlashQuotes(filter);
      filters = splitLoggerString(splitString(filterString));
    }
  }

  public LoggerFilterDecider(List<String> filters) {
    if (!filters.isEmpty()) {
      filterString = prettyFormatList(filters);
      this.filters = splitLoggerString(filters);
    }
  }

  public boolean shouldOverrideLogLevel(String loggedClassName, String message) {
    return filters != null
        && !message.contains(filterString)
        && filters.stream()
            .anyMatch(r -> loggedClassName.contains(r[0]) && message.startsWith(r[1]));
  }

  private List<String[]> splitLoggerString(List<String> filters) {
    return filters.stream()
        .map(
            s -> {
              String[] parts = s.split("\\|");
              return new String[] {parts[0], parts.length > 1 ? unwrapQuotes(parts[1]) : ""};
            })
        .collect(Collectors.toList());
  }
}
