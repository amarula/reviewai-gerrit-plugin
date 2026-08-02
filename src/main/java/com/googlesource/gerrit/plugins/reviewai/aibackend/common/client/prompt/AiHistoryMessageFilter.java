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

import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.CODE_DELIMITER;

public class AiHistoryMessageFilter {
  public boolean shouldIncludeReviewComment(AiReviewHistoryInfo.Entry entry) {
    return entry != null
        && Settings.OPENAI_ROLE_ASSISTANT.equals(entry.getRole())
        && !entry.isSystemMessage()
        && firstNonBlank(entry.getId(), entry.getChangeMessageId()) != null
        && shouldIncludeMessage(entry.getMessage());
  }

  public boolean shouldIncludeMessage(String message) {
    String normalized = normalizedMessage(message);
    return !normalized.isEmpty() && !isHistoryNoise(normalized);
  }

  private String normalizedMessage(String message) {
    String normalized = message == null ? "" : message.strip();
    if (normalized.startsWith(CODE_DELIMITER)) {
      normalized =
          normalized.replaceFirst("^```[^\\n]*\\n?", "").replaceFirst("\\n?```$", "").strip();
    }
    return normalized;
  }

  private boolean isHistoryNoise(String message) {
    return message.startsWith("DYNAMIC CONFIGURATION SETTINGS")
        || message.startsWith("ReviewAI Message: Dynamic configuration")
        || message.startsWith("Dynamic configuration")
        || message.startsWith("ReviewAI Message: No update to show for this Change Set")
        || message.startsWith("No update to show for this Change Set")
        || message.startsWith("Uploaded patch set ")
        || message.startsWith("Outdated Votes:")
        || message.contains("\nOutdated Votes:");
  }

  private String firstNonBlank(String primary, String fallback) {
    if (primary != null && !primary.isBlank()) {
      return primary;
    }
    return fallback == null || fallback.isBlank() ? null : fallback;
  }
}
