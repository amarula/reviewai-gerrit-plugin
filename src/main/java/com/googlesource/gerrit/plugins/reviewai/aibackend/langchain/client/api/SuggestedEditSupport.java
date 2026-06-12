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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import java.util.List;

final class SuggestedEditSupport {
  static final String COMMIT_MESSAGE_FILENAME = "/COMMIT_MSG";

  private static final String SUGGESTION_FENCE = "```suggestion";

  private SuggestedEditSupport() {}

  static List<AiReplyItem> responseReplies(AiResponseContent responseContent) {
    if (responseContent.getReplies() == null) {
      return List.of();
    }
    return responseContent.getReplies().stream()
        .filter(reply -> reply.getReply() != null && !reply.getReply().isBlank())
        .toList();
  }

  static String extractCommitMessage(String patchSet) {
    int separatorIndex = patchSet.indexOf("\n---\n");
    String header = separatorIndex >= 0 ? patchSet.substring(0, separatorIndex) : patchSet;
    int subjectIndex = header.indexOf("Subject: ");
    if (subjectIndex >= 0) {
      header = header.substring(subjectIndex + "Subject: ".length());
    }
    int changeIdIndex = header.indexOf("\nChange-Id:");
    if (changeIdIndex >= 0) {
      header = header.substring(0, changeIdIndex);
    }
    return header.strip();
  }

  static boolean hasSuggestionFence(AiReplyItem suggestion) {
    return suggestion.getReply().contains(SUGGESTION_FENCE);
  }

  static boolean isCommitMessageFile(String filename) {
    return COMMIT_MESSAGE_FILENAME.equals(filename);
  }

  static boolean hasCodeSuggestionTarget(AiReplyItem suggestion) {
    return suggestion.getFilename() != null
        && !suggestion.getFilename().isBlank()
        && !isCommitMessageFile(suggestion.getFilename())
        && suggestion.getLineNumber() != null
        && suggestion.getCodeSnippet() != null
        && !suggestion.getCodeSnippet().isBlank();
  }
}
