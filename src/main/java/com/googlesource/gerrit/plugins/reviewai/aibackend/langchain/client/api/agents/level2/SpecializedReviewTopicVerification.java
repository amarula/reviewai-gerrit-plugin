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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SpecializedReviewTopicVerification {
  private static final Pattern TOPIC_PATCH_ORIGIN_PATTERN =
      Pattern.compile("(?m)^ReviewAI origin: (reviewai-topic-change-\\d+/)\\R");

  private SpecializedReviewTopicVerification() {}

  static List<TopicVerificationPatch> topicVerificationPatches(String patchSet) {
    if (patchSet == null || patchSet.isBlank()) {
      return List.of();
    }
    Matcher matcher = TOPIC_PATCH_ORIGIN_PATTERN.matcher(patchSet);
    List<TopicPatchOrigin> origins = new ArrayList<>();
    while (matcher.find()) {
      origins.add(new TopicPatchOrigin(matcher.start(), matcher.group(1)));
    }
    if (origins.size() < 2) {
      return List.of();
    }

    String topicHeader = patchSet.substring(0, origins.getFirst().start()).strip();
    return topicVerificationPatches(patchSet, origins, topicHeader);
  }

  static String verificationConversationSuffix(TopicVerificationPatch topicPatch) {
    return topicPatch.prefix().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("_+$", "");
  }

  static SpecializedReviewFindings findingsForTopicPrefix(
      SpecializedReviewFindings findings, String prefix) {
    findings.normalize();
    SpecializedReviewFindings filtered = new SpecializedReviewFindings();
    filtered.setConcerns(
        findings.getConcerns().stream()
            .filter(concern -> concernBelongsToTopicPrefix(concern, prefix))
            .map(SpecializedReviewConcernIds::copyConcern)
            .toList());
    filtered.setDismissedConcerns(
        findings.getDismissedConcerns().stream()
            .filter(concern -> concernBelongsToTopicPrefix(concern, prefix))
            .map(SpecializedReviewConcernIds::copyConcern)
            .toList());
    return filtered;
  }

  static AiResponseContent combinedVerificationResponse(List<AiResponseContent> responses) {
    AiResponseContent combined = new AiResponseContent("");
    combined.setReplies(
        responses.stream()
            .filter(response -> response != null && response.getReplies() != null)
            .flatMap(response -> response.getReplies().stream())
            .toList());
    responses.stream()
        .filter(response -> response != null && response.getChangeId() != null)
        .findFirst()
        .ifPresent(response -> combined.setChangeId(response.getChangeId()));
    return combined;
  }

  private static List<TopicVerificationPatch> topicVerificationPatches(
      String patchSet, List<TopicPatchOrigin> origins, String topicHeader) {
    List<TopicVerificationPatch> topicPatches = new ArrayList<>();
    for (int index = 0; index < origins.size(); index++) {
      TopicPatchOrigin origin = origins.get(index);
      int end = index + 1 < origins.size() ? origins.get(index + 1).start() : patchSet.length();
      String patchSection = patchSet.substring(origin.start(), end).strip();
      if (!topicHeader.isEmpty()) {
        patchSection = topicHeader + "\n\n" + patchSection;
      }
      topicPatches.add(new TopicVerificationPatch(origin.prefix(), patchSection));
    }
    return topicPatches;
  }

  private static boolean concernBelongsToTopicPrefix(
      ReviewConcern concern, String prefix) {
    concern.normalize();
    return locationsBelongToTopicPrefix(concern.getLocations(), prefix);
  }

  private static boolean locationsBelongToTopicPrefix(
      List<ConcernLocation> locations, String prefix) {
    if (locations.isEmpty()) {
      return true;
    }
    return locations.stream()
        .map(ConcernLocation::getFilename)
        .anyMatch(filename -> filename == null || filename.isBlank() || filename.startsWith(prefix));
  }

  private record TopicPatchOrigin(int start, String prefix) {}

  record TopicVerificationPatch(String prefix, String patchSet) {}
}
