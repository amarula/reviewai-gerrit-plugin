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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class SuggestedPatchSetCandidate {
  // Set suggestion thresholds to match Gerrit's default 16 KiB comment limit, and use an 8 KiB
  // limit for final patch set suggestions only.
  static final int MAX_DISPLAYED_FINAL_PATCHSET_SIZE_BYTES = 8 << 10;
  static final int MAX_TOTAL_MESSAGE_SIZE_BYTES = 16 << 10;
  private static final String COMMIT_MESSAGE_SEPARATOR = "\n---";

  private SuggestedPatchSetCandidate() {}

  public static String merge(
      String originalPatchSet, AiResponseContent suggestion, ReviewAssistantStage assistantStage) {
    String suggestionText = suggestionText(suggestion);
    if (suggestionText.isBlank()) {
      return originalPatchSet;
    }
    if (assistantStage == ReviewAssistantStage.REVIEW_COMMIT_MESSAGE) {
      return mergeCommitMessage(originalPatchSet, suggestionText);
    }
    return mergePatchSetFix(originalPatchSet, suggestionText);
  }

  public static void appendFinalPatchSet(
      AiResponseContent suggestion,
      String finalPatchSet,
      ReviewAssistantStage assistantStage,
      String emptyFinalPatchSetMessage) {
    if (assistantStage != ReviewAssistantStage.REVIEW_CODE
        || suggestion == null
        || suggestion.getReplies() == null) {
      return;
    }
    suggestion.getReplies().stream()
        .filter(reply -> reply.getReply() != null && !reply.getReply().isBlank())
        .findFirst()
        .ifPresent(
            reply ->
                reply.setReply(
                    appendFinalPatchSet(
                        reply.getReply(), finalPatchSet, emptyFinalPatchSetMessage)));
  }

  public static boolean hasDisplayablePatchSet(String finalPatchSet) {
    return !displayPatchSet(finalPatchSet).isBlank();
  }

  public static boolean isEmptyFinalPatchSetResponse(
      AiResponseContent suggestion, String emptyFinalPatchSetMessage) {
    return suggestion != null
        && suggestion.getReplies() != null
        && suggestion.getReplies().stream()
            .map(AiReplyItem::getReply)
            .anyMatch(emptyFinalPatchSetMessage::equals);
  }

  private static String mergePatchSetFix(String originalPatchSet, String suggestionText) {
    return PatchSetMerger.merge(originalPatchSet, suggestionText);
  }

  static String suggestionText(AiResponseContent suggestion) {
    return normalizeSuggestionText(suggestion);
  }

  private static String mergeCommitMessage(String originalPatchSet, String suggestionText) {
    int separatorIndex = originalPatchSet.indexOf(COMMIT_MESSAGE_SEPARATOR);
    if (separatorIndex < 0) {
      return suggestionText.strip() + "\n\n" + originalPatchSet.stripLeading();
    }
    return suggestionText.strip() + originalPatchSet.substring(separatorIndex);
  }

  private static String appendFinalPatchSet(
      String reply, String finalPatchSet, String emptyFinalPatchSetMessage) {
    String displayPatchSet = displayPatchSet(finalPatchSet);
    if (displayPatchSet.isBlank()) {
      return emptyFinalPatchSetMessage;
    }
    if (displayPatchSet.getBytes(StandardCharsets.UTF_8).length
        > MAX_DISPLAYED_FINAL_PATCHSET_SIZE_BYTES) {
      return reply;
    }
    String message =
        reply.stripTrailing()
        + "\n\nFinal patchset:\n```diff\n"
        + displayPatchSet
        + "\n```";
    return message.getBytes(StandardCharsets.UTF_8).length < MAX_TOTAL_MESSAGE_SIZE_BYTES
        ? message
        : reply;
  }

  private static String displayPatchSet(String finalPatchSet) {
    int firstDiffIndex = finalPatchSet.indexOf("diff --git ");
    if (firstDiffIndex < 0) {
      return "";
    }
    return finalPatchSet.substring(firstDiffIndex).strip();
  }

  private static String normalizeSuggestionText(AiResponseContent suggestion) {
    if (suggestion == null || suggestion.getReplies() == null) {
      return "";
    }
    return String.join(
            "\n\n",
            suggestion.getReplies().stream()
                .map(AiReplyItem::getReply)
                .filter(reply -> reply != null && !reply.isBlank())
                .map(SuggestedPatchSetCandidate::stripSuggestionPrefix)
                .toList())
        .strip();
  }

  private static String stripSuggestionPrefix(String reply) {
    List<String> prefixes = List.of("Suggested patchset fix:", "Suggested commit message:");
    String normalized = stripMarkdownFence(reply.strip());
    for (String prefix : prefixes) {
      if (normalized.regionMatches(true, 0, prefix, 0, prefix.length())) {
        return stripMarkdownFence(normalized.substring(prefix.length()).strip());
      }
    }
    return normalized;
  }

  private static String stripMarkdownFence(String text) {
    String stripped = text.strip();
    if (!stripped.startsWith("```")) {
      return stripped;
    }
    int firstLineEnd = stripped.indexOf('\n');
    int lastFenceStart = stripped.lastIndexOf("```");
    if (firstLineEnd < 0 || lastFenceStart <= firstLineEnd) {
      return stripped;
    }
    return stripped.substring(firstLineEnd + 1, lastFenceStart).strip();
  }
}
