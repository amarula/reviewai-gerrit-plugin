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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.review;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class RepeatedCommentReferenceFormatter {
  private final Localizer localizer;
  private final RepeatedCommentResolver repeatedCommentResolver;
  private final GerritCommentLinkFormatter commentLinkFormatter;

  public RepeatedCommentReferenceFormatter(
      GerritClient gerritClient,
      ChangeSetData changeSetData,
      Localizer localizer,
      String canonicalWebUrl) {
    this.localizer = localizer;
    this.repeatedCommentResolver = new RepeatedCommentResolver(gerritClient, changeSetData);
    this.commentLinkFormatter = new GerritCommentLinkFormatter(changeSetData, canonicalWebUrl);
  }

  public Optional<String> format(List<AiReplyItem> repeatedReplyItems, GerritChange change) {
    if (repeatedReplyItems == null || repeatedReplyItems.isEmpty()) {
      return Optional.empty();
    }

    Map<String, String> repeatedCommentReferences = new LinkedHashMap<>();
    for (AiReplyItem replyItem : repeatedReplyItems) {
      repeatedCommentResolver
          .resolve(replyItem, change)
          .flatMap(comment -> commentLinkFormatter.toCommentLink(comment, replyItem, change))
          .ifPresent(
              reference ->
                  repeatedCommentReferences.compute(
                      reference,
                      (ignored, reason) -> hasText(reason) ? reason : normalizedReason(replyItem)));
    }

    if (repeatedCommentReferences.isEmpty()) {
      return Optional.of(localizer.getText("message.repeated.comments.still.hold.no.references"));
    }

    boolean singleComment = repeatedCommentReferences.size() == 1;
    String messageKey =
        singleComment
            ? "message.repeated.comment.still.holds"
            : "message.repeated.comments.still.hold";
    if (singleComment) {
      Map.Entry<String, String> reference = repeatedCommentReferences.entrySet().iterator().next();
      String message =
          SystemMessageFormatter.getLocalizedMessage(localizer, messageKey, reference.getKey());
      return Optional.of(appendStatusReason(message, reference.getValue()));
    }
    List<String> commentReferences =
        repeatedCommentReferences.entrySet().stream()
            .map(reference -> appendStatusReason(reference.getKey(), reference.getValue()))
            .toList();
    return Optional.of(
        SystemMessageFormatter.getLocalizedMessage(
            localizer,
            messageKey,
            "\n\n" + GerritCommentLinkFormatter.toMarkdownList(commentReferences)));
  }

  private static String normalizedReason(AiReplyItem replyItem) {
    String reason = replyItem.getRepeatedReason();
    return hasText(reason) ? reason.strip() : "";
  }

  private static String appendStatusReason(String text, String reason) {
    return hasText(reason) ? text + ": " + reason : text;
  }

  private static boolean hasText(String text) {
    return text != null && !text.isBlank();
  }
}
