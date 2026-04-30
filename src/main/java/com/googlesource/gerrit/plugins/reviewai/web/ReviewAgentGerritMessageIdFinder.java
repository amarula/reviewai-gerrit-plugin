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

package com.googlesource.gerrit.plugins.reviewai.web;

import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.common.ChangeMessageInfo;
import com.google.gerrit.extensions.common.CommentInfo;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

class ReviewAgentGerritMessageIdFinder {
  Optional<String> findPostedChangeMessageId(
      ChangeApi changeApi, ReviewResult reviewResult, String postedMessage) throws Exception {
    Optional<String> commentMessageId =
        Optional.ofNullable(changeApi.commentsRequest().get()).stream()
            .flatMap(comments -> comments.values().stream())
            .flatMap(List::stream)
            .filter(comment -> postedMessage.equals(comment.message))
            .filter(comment -> comment.changeMessageId != null && !comment.changeMessageId.isBlank())
            .max(
                Comparator.comparing(
                        ReviewAgentGerritMessageIdFinder::getCommentUpdated,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(comment -> comment.id, Comparator.nullsLast(String::compareTo)))
            .map(comment -> comment.changeMessageId);
    if (commentMessageId.isPresent()) {
      return commentMessageId;
    }
    return findPostedChangeMessageId(reviewResult, changeApi, postedMessage);
  }

  private Optional<String> findPostedChangeMessageId(
      ReviewResult reviewResult, ChangeApi changeApi, String postedMessage) throws Exception {
    Collection<ChangeMessageInfo> messages =
        Optional.ofNullable(reviewResult)
            .map(result -> result.changeInfo)
            .map(changeInfo -> changeInfo.messages)
            .orElse(null);
    if (messages == null) {
      messages = changeApi.messages();
    }
    if (messages == null) {
      return Optional.empty();
    }
    return messages.stream()
        .filter(message -> postedMessage.equals(stripPatchSetPrefix(message.message)))
        .filter(message -> message.id != null && !message.id.isBlank())
        .max(
            Comparator.comparing(
                    (ChangeMessageInfo message) -> message.date,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(message -> message.id, Comparator.nullsLast(String::compareTo)))
        .map(message -> message.id);
  }

  private static java.time.Instant getCommentUpdated(CommentInfo comment) {
    return comment == null ? null : comment.getUpdated();
  }

  private static String stripPatchSetPrefix(String message) {
    if (message == null) {
      return "";
    }
    return message.replaceFirst("^Patch Set \\d+:\\s*", "").trim();
  }
}
