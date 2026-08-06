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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.PendingReviewConcernUpdates;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ReviewConcernLedgerOperations {
  public ReviewerConcerns reviewerConcerns(
      ReviewConcernLedger ledger, ConcernReviewerId reviewer) {
    ledger.normalize();
    return ledger.getReviewers().stream()
        .filter(entry -> reviewer.equals(entry.getReviewer()))
        .findFirst()
        .orElseGet(() -> emptyReviewerConcerns(reviewer));
  }

  public AiResponseContent initializeLedger(
      AiResponseContent response, GerritChange change, ConcernReviewerId reviewer) {
    if (response == null) {
      return null;
    }
    ReviewerConcerns concerns =
        mapNewConcerns(response.getReplies(), reviewer, Set.of(), false);
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(concerns));
    attachPendingLedger(response, change, ledger);
    return response;
  }

  public AiResponseContent completeFollowUp(
      AiResponseContent response,
      GerritChange change,
      ReviewConcernLedger previousLedger,
      ReviewerConcerns reviewedConcerns) {
    return completeFollowUp(
        response, change, previousLedger, reviewedConcerns, true);
  }

  public AiResponseContent completeFollowUp(
      AiResponseContent response,
      GerritChange change,
      ReviewConcernLedger previousLedger,
      ReviewerConcerns reviewedConcerns,
      boolean preserveOtherReviewers) {
    if (response == null) {
      return null;
    }
    Set<String> existingIds =
        reviewedConcerns.getConcerns().stream()
            .map(ReviewConcern::getId)
            .collect(Collectors.toSet());
    ReviewerConcerns newConcerns =
        mapNewConcerns(
            response.getReplies(), reviewedConcerns.getReviewer(), existingIds, true);
    List<ReviewConcern> currentConcerns = new ArrayList<>(reviewedConcerns.getConcerns());
    currentConcerns.addAll(newConcerns.getConcerns());
    ReviewerConcerns currentReviewerConcerns = new ReviewerConcerns();
    currentReviewerConcerns.setReviewer(reviewedConcerns.getReviewer());
    currentReviewerConcerns.setConcerns(currentConcerns);

    List<AiReplyItem> replies =
        reviewedConcerns.getConcerns().stream()
            .filter(concern -> concern.getStatus() == ConcernStatus.PRESENT)
            .map(this::toRepeatedReply)
            .collect(Collectors.toCollection(ArrayList::new));
    if (response.getReplies() != null) {
      replies.addAll(response.getReplies());
    }
    response.setReplies(replies);
    ReviewConcernLedger ledger =
        preserveOtherReviewers
            ? replaceReviewer(previousLedger, currentReviewerConcerns)
            : ledgerForReviewer(currentReviewerConcerns);
    attachPendingLedger(response, change, ledger);
    return response;
  }

  public ReviewConcernLedger mergeReviewerUpdates(
      ReviewConcernLedger ledger, ReviewConcernLedger updates) {
    ReviewConcernLedger merged = ledger;
    updates.normalize();
    for (ReviewerConcerns update : updates.getReviewers()) {
      merged = replaceReviewer(merged, update);
    }
    return merged;
  }

  public void attachPendingLedger(
      AiResponseContent response, GerritChange change, ReviewConcernLedger ledger) {
    PendingReviewConcernUpdates updates = response.getPendingConcernUpdates();
    if (updates == null) {
      updates = new PendingReviewConcernUpdates();
      response.setPendingConcernUpdates(updates);
    }
    updates.put(change.getFullChangeId(), ledger);
  }

  public AiReplyItem toRepeatedReply(ReviewConcern concern) {
    ReviewConcern repeatedConcern = concern.copy();
    repeatedConcern.setRepeated(true);
    repeatedConcern.setRepeatedReason(concern.getStatusReason());
    if (repeatedConcern.getPreviousCommentId() == null
        || repeatedConcern.getPreviousCommentId().isBlank()) {
      repeatedConcern.setPreviousCommentId(concern.getId());
    }
    return ReviewConcernReplyMapper.toReply(repeatedConcern);
  }

  private ReviewerConcerns mapNewConcerns(
      List<AiReplyItem> replies,
      ConcernReviewerId reviewer,
      Set<String> reservedIds,
      boolean enforceNewIssue) {
    Set<String> usedIds = new HashSet<>(reservedIds);
    List<ReviewConcern> concerns = new ArrayList<>();
    if (replies != null) {
      for (AiReplyItem reply : replies) {
        if (reply == null || reply.getReply() == null || reply.getReply().isBlank()) {
          continue;
        }
        String concernId = reply.getConcernId();
        if (concernId == null || concernId.isBlank() || usedIds.contains(concernId)) {
          concernId = newConcernId(usedIds);
        }
        usedIds.add(concernId);
        reply.setConcernId(concernId);
        if (enforceNewIssue) {
          reply.setRepeated(false);
          reply.setRepeatedReason(null);
          reply.setRepetitionReplyId(null);
        }
        concerns.add(ReviewConcernReplyMapper.fromReply(reply, reviewer, concernId));
      }
    }
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setReviewer(reviewer);
    reviewerConcerns.setConcerns(concerns);
    return reviewerConcerns;
  }

  private String newConcernId(Set<String> usedIds) {
    String concernId;
    do {
      concernId = "concern-" + UUID.randomUUID();
    } while (usedIds.contains(concernId));
    return concernId;
  }

  private ReviewerConcerns emptyReviewerConcerns(ConcernReviewerId reviewer) {
    ReviewerConcerns concerns = new ReviewerConcerns();
    concerns.setReviewer(reviewer);
    return concerns;
  }

  private ReviewConcernLedger replaceReviewer(
      ReviewConcernLedger ledger, ReviewerConcerns replacement) {
    ledger.normalize();
    List<ReviewerConcerns> reviewers = new ArrayList<>(ledger.getReviewers());
    reviewers.removeIf(entry -> replacement.getReviewer().equals(entry.getReviewer()));
    reviewers.add(replacement);
    ReviewConcernLedger updated = new ReviewConcernLedger();
    updated.setSchemaVersion(ledger.getSchemaVersion());
    updated.setReviewers(reviewers);
    return updated;
  }

  private ReviewConcernLedger ledgerForReviewer(ReviewerConcerns concerns) {
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(concerns));
    return ledger;
  }
}
