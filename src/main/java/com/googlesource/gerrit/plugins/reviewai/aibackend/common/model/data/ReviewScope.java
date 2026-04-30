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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data;

import java.util.Arrays;
import java.util.List;

public enum ReviewScope {
  FULL("full"),
  PATCHSET("patchset"),
  COMMIT_MESSAGE("commit_message");

  private final String commandOptionValue;

  ReviewScope(String commandOptionValue) {
    this.commandOptionValue = commandOptionValue;
  }

  public String getCommandOptionValue() {
    return commandOptionValue;
  }

  public static List<String> commandOptionValues() {
    return Arrays.stream(values()).map(ReviewScope::getCommandOptionValue).toList();
  }

  public static List<String> reviewCommandOptionValues() {
    return List.of(PATCHSET.getCommandOptionValue(), COMMIT_MESSAGE.getCommandOptionValue());
  }

  public static ReviewScope fromCommandOption(String value) {
    for (ReviewScope scope : values()) {
      if (scope.commandOptionValue.equals(value)) {
        return scope;
      }
    }
    throw new IllegalArgumentException("Unsupported review scope: " + value);
  }
}
