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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages;

import java.util.regex.Pattern;

public final class ReviewAgentMessageSanitizer {
  private static final Pattern LEGACY_REQUEST_MARKER_PATTERN =
      Pattern.compile("\\s*<!--\\s*reviewai-request-id:\\s*([A-Za-z0-9._:-]+)\\s*-->\\s*");

  private ReviewAgentMessageSanitizer() {}

  public static String removeLegacyRequestMarker(String message) {
    if (message == null) {
      return null;
    }
    return LEGACY_REQUEST_MARKER_PATTERN.matcher(message).replaceAll("").trim();
  }
}
