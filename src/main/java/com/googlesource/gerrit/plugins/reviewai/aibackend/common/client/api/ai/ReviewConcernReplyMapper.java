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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import java.util.List;

public final class ReviewConcernReplyMapper {
  private ReviewConcernReplyMapper() {}

  public static ReviewConcern fromReply(
      AiReplyItem reply, ConcernReviewerId reviewer, String generatedConcernId) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId(firstNonBlank(reply.getConcernId(), generatedConcernId));
    concern.setStatus(ConcernStatus.PRESENT);
    concern.setDescription(reply.getReply());
    concern.setReply(reply.getReply());
    concern.setScore(reply.getScore());
    concern.setRelevance(reply.getRelevance());
    concern.setRepeated(reply.isRepeated());
    concern.setRepeatedReason(reply.getRepeatedReason());
    concern.setPreviousCommentId(reply.getRepetitionReplyId());
    concern.setReviewers(List.of(reviewer));
    if (hasLocation(reply)) {
      ConcernLocation location = new ConcernLocation();
      location.setFilename(reply.getFilename());
      location.setLineNumber(reply.getLineNumber());
      location.setCodeSnippet(reply.getCodeSnippet());
      concern.setLocations(List.of(location));
    }
    concern.normalize();
    return concern;
  }

  public static AiReplyItem toReply(ReviewConcern concern) {
    concern.normalize();
    ConcernLocation location = concern.getLocations().stream().findFirst().orElse(null);
    return AiReplyItem.builder()
        .concernId(concern.getId())
        .reply(firstNonBlank(concern.getReply(), concern.getDescription()))
        .score(concern.getScore())
        .relevance(concern.getRelevance())
        .repeated(Boolean.TRUE.equals(concern.getRepeated()))
        .repeatedReason(concern.getRepeatedReason())
        .repetitionReplyId(concern.getPreviousCommentId())
        .filename(location == null ? null : location.getFilename())
        .lineNumber(location == null ? null : location.getLineNumber())
        .codeSnippet(location == null ? null : location.getCodeSnippet())
        .build();
  }

  private static boolean hasLocation(AiReplyItem reply) {
    return reply.getFilename() != null
        || reply.getLineNumber() != null
        || reply.getCodeSnippet() != null;
  }

  private static String firstNonBlank(String primary, String fallback) {
    return primary == null || primary.isBlank() ? fallback : primary;
  }
}
