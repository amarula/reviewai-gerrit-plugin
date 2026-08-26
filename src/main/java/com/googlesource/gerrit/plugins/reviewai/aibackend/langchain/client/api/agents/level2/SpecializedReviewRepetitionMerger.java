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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SpecializedReviewRepetitionMerger {
  private SpecializedReviewRepetitionMerger() {}

  static SpecializedReviewFindings copyRepeatedAnnotations(
      SpecializedReviewFindings targetFindings, SpecializedReviewFindings sourceFindings) {
    targetFindings.normalize();
    sourceFindings.normalize();
    Map<String, ReviewConcern> sourceConcernsByRawId =
        sourceConcernsByRawId(sourceFindings);
    for (ReviewConcern targetConcern : targetFindings.getConcerns()) {
      List<String> rawConcernIds = SpecializedReviewConcernIds.rawConcernIds(targetConcern);
      boolean repeated =
          !rawConcernIds.isEmpty()
              && rawConcernIds.stream()
                  .allMatch(
                      id ->
                          sourceConcernsByRawId.containsKey(id)
                              && Boolean.TRUE.equals(sourceConcernsByRawId.get(id).getRepeated()));
      if (!repeated) {
        clearRepeatedAnnotation(targetConcern);
        continue;
      }
      rawConcernIds.stream()
          .map(sourceConcernsByRawId::get)
          .filter(concern -> concern != null && Boolean.TRUE.equals(concern.getRepeated()))
          .findFirst()
          .ifPresent(sourceConcern -> applyRepeatedAnnotation(targetConcern, sourceConcern));
    }
    return targetFindings;
  }

  static SpecializedReviewFindings applyHistoricalRepetition(
      SpecializedReviewFindings consolidatedFindings,
      SpecializedReviewFindings.HistoricalRepetitionResult historicalRepetitionResult) {
    consolidatedFindings.normalize();
    Map<String, SpecializedReviewFindings.HistoricalRepetitionAnnotation> annotationsById =
        new LinkedHashMap<>();
    for (SpecializedReviewFindings.HistoricalRepetitionAnnotation annotation :
        historicalRepetitionResult.getAnnotations()) {
      annotationsById.put(annotation.getConcernId(), annotation);
    }
    for (ReviewConcern concern : consolidatedFindings.getConcerns()) {
      List<String> rawConcernIds = SpecializedReviewConcernIds.rawConcernIds(concern);
      if (!rawConcernIds.isEmpty()) {
        concern.setId("c-" + rawConcernIds.getFirst());
      }
      boolean repeated =
          !rawConcernIds.isEmpty()
              && rawConcernIds.stream()
                  .allMatch(
                      id ->
                          annotationsById.containsKey(id)
                              && annotationsById.get(id).isRepeated());
      log.debug(
          "Level 2 historical repetition merge: concernId={}, mergedConcernIds={}, repeated={}",
          concern.getId(),
          rawConcernIds,
          repeated);
      if (!repeated) {
        clearRepeatedAnnotation(concern);
        continue;
      }
      annotationsById.values().stream()
          .filter(annotation -> rawConcernIds.contains(annotation.getConcernId()))
          .filter(SpecializedReviewFindings.HistoricalRepetitionAnnotation::isRepeated)
          .findFirst()
          .ifPresent(annotation -> applyRepeatedAnnotation(concern, annotation));
    }
    return consolidatedFindings;
  }

  static AiResponseContent inheritRepeatedAnnotations(
      AiResponseContent response, SpecializedReviewFindings findings) {
    if (response == null || response.getReplies() == null) {
      return response;
    }
    findings.normalize();
    List<AiReplyItem> replies = response.getReplies();
    for (int i = 0; i < replies.size(); i++) {
      AiReplyItem reply = replies.get(i);
      Optional<ReviewConcern> matchedConcern =
          matchedConcernForReply(reply, findings.getConcerns(), i, replies.size());
      if (matchedConcern.isEmpty() || !Boolean.TRUE.equals(matchedConcern.get().getRepeated())) {
        continue;
      }
      ReviewConcern concern = matchedConcern.get();
      applyRepeatedAnnotation(reply, concern);
      log.debug(
          "Level 2 final reply inherited repeated annotation from concernId={}",
          concern.getId());
    }
    return response;
  }

  static SpecializedReviewFindings clearRepeatedAnnotations(
      SpecializedReviewFindings findings) {
    findings.normalize();
    findings.getConcerns().forEach(SpecializedReviewRepetitionMerger::clearRepeatedAnnotation);
    return findings;
  }

  static AiResponseContent clearRepeatedAnnotations(AiResponseContent response) {
    if (response == null || response.getReplies() == null) {
      return response;
    }
    response.getReplies().forEach(SpecializedReviewRepetitionMerger::clearRepeatedAnnotation);
    return response;
  }

  private static Map<String, ReviewConcern> sourceConcernsByRawId(
      SpecializedReviewFindings sourceFindings) {
    Map<String, ReviewConcern> sourceConcernsByRawId = new LinkedHashMap<>();
    for (ReviewConcern sourceConcern : sourceFindings.getConcerns()) {
      for (String rawConcernId : SpecializedReviewConcernIds.rawConcernIds(sourceConcern)) {
        sourceConcernsByRawId.put(rawConcernId, sourceConcern);
      }
    }
    return sourceConcernsByRawId;
  }

  static Optional<ReviewConcern> matchedConcernForReply(
      AiReplyItem reply,
      List<ReviewConcern> concerns,
      int replyIndex,
      int replyCount) {
    // The verification stage returns one reply for each supplied concern in the same order. Use
    // that unambiguous mapping before location matching: multiple agents can validly report
    // distinct concerns on the same source line.
    if (replyCount == concerns.size() && replyIndex < concerns.size()) {
      return Optional.of(concerns.get(replyIndex));
    }
    Optional<ReviewConcern> locationMatch =
        concerns.stream().filter(concern -> matchesAnyLocation(reply, concern)).findFirst();
    if (locationMatch.isPresent()) {
      return locationMatch;
    }
    return Optional.empty();
  }

  private static boolean matchesAnyLocation(
      AiReplyItem reply, ReviewConcern concern) {
    concern.normalize();
    return concern.getLocations().stream().anyMatch(location -> matchesLocation(reply, location));
  }

  private static boolean matchesLocation(
      AiReplyItem reply, ConcernLocation location) {
    boolean filenameMatches =
        reply.getFilename() != null
            && location.getFilename() != null
            && reply.getFilename().equals(location.getFilename());
    boolean lineMatches =
        reply.getLineNumber() != null
            && location.getLineNumber() != null
            && reply.getLineNumber().equals(location.getLineNumber());
    boolean snippetMatches =
        reply.getCodeSnippet() != null
            && location.getCodeSnippet() != null
            && reply.getCodeSnippet().equals(location.getCodeSnippet());
    return filenameMatches && (lineMatches || snippetMatches);
  }

  private static void clearRepeatedAnnotation(ReviewConcern concern) {
    concern.setRepeated(false);
    concern.setPreviousCommentId("");
    concern.setRepeatedReason("");
  }

  private static void clearRepeatedAnnotation(AiReplyItem reply) {
    reply.setRepeated(false);
    reply.setRepetitionReplyId("");
    reply.setRepeatedReason("");
  }

  private static void applyRepeatedAnnotation(
      ReviewConcern concern,
      SpecializedReviewFindings.HistoricalRepetitionAnnotation annotation) {
    concern.setRepeated(true);
    concern.setPreviousCommentId(nullToEmpty(annotation.getPastCommentId()));
    concern.setRepeatedReason(nullToEmpty(annotation.getReason()));
  }

  private static void applyRepeatedAnnotation(
      ReviewConcern targetConcern, ReviewConcern sourceConcern) {
    targetConcern.setRepeated(true);
    targetConcern.setPreviousCommentId(nullToEmpty(sourceConcern.getPreviousCommentId()));
    targetConcern.setRepeatedReason(nullToEmpty(sourceConcern.getRepeatedReason()));
  }

  private static void applyRepeatedAnnotation(
      AiReplyItem reply, ReviewConcern concern) {
    reply.setRepeated(true);
    reply.setRepetitionReplyId(nullToEmpty(concern.getPreviousCommentId()));
    reply.setRepeatedReason(nullToEmpty(concern.getRepeatedReason()));
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
