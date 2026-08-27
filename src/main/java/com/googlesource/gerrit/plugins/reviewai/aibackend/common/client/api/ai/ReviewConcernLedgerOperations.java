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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class ReviewConcernLedgerOperations {
  private final Localizer localizer;
  private final Configuration config;

  public ReviewConcernLedgerOperations(Localizer localizer, Configuration config) {
    this.localizer = localizer;
    this.config = config;
  }

  public ReviewerConcerns reviewerConcerns(
      ReviewConcernLedger ledger, ConcernReviewerId reviewer) {
    ledger.normalize();
    return ledger.getReviewers().stream()
        .filter(entry -> reviewer.equals(entry.getReviewer()))
        .findFirst()
        .orElseGet(() -> emptyReviewerConcerns(reviewer));
  }

  /** Marks concerns owned by disabled scopes or specialized agents as unassessed. */
  public ReviewConcernLedger markDisabledConcernsSkipped(
      ReviewConcernLedger ledger, ReviewFeedbackMemory feedback) {
    if (ledger == null || feedback == null || !hasDisabledReviews(feedback)) {
      return ledger;
    }
    ledger.normalize();
    List<ReviewerConcerns> reviewers = new ArrayList<>();
    for (ReviewerConcerns reviewerConcerns : ledger.getReviewers()) {
      ReviewerConcerns updatedReviewer = new ReviewerConcerns();
      updatedReviewer.setReviewer(reviewerConcerns.getReviewer());
      List<ReviewConcern> concerns = new ArrayList<>();
      for (ReviewConcern concern : reviewerConcerns.getConcerns()) {
        ReviewConcern updatedConcern = concern.copy();
        String skippedReason =
            skippedReason(feedback, reviewerConcerns.getReviewer(), concern);
        if (skippedReason != null && concern.getStatus() != ConcernStatus.DISMISSED) {
          updatedConcern.setStatus(ConcernStatus.SKIPPED);
          updatedConcern.setStatusReason(skippedReason);
        }
        concerns.add(updatedConcern);
      }
      updatedReviewer.setConcerns(concerns);
      reviewers.add(updatedReviewer);
    }
    ReviewConcernLedger updatedLedger = new ReviewConcernLedger();
    updatedLedger.setSchemaVersion(ledger.getSchemaVersion());
    updatedLedger.setLastReviewedCommit(ledger.getLastReviewedCommit());
    updatedLedger.setReviewers(reviewers);
    return updatedLedger;
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
            .map(
                concern ->
                    toPresentReply(
                        previousLedger, reviewedConcerns.getReviewer(), concern))
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

  public AiReplyItem toPresentReply(
      ReviewConcernLedger previousLedger, ConcernReviewerId reviewer, ReviewConcern concern) {
    Optional<ConcernStatus> previousStatus = previousStatus(previousLedger, reviewer, concern);
    if (previousStatus.filter(ConcernStatus::shouldResolveGerritThread).isEmpty()) {
      return toRepeatedReply(concern);
    }
    ReviewConcern reactivatedConcern = concern.copy();
    reactivatedConcern.setRepeated(false);
    reactivatedConcern.setRepeatedReason(null);
    AiReplyItem reply = ReviewConcernReplyMapper.toReply(reactivatedConcern);
    reply.setReply(reactivationHeading(previousStatus.orElseThrow()) + "\n\n" + reply.getReply());
    return reply;
  }

  private Optional<ConcernStatus> previousStatus(
      ReviewConcernLedger previousLedger, ConcernReviewerId reviewer, ReviewConcern concern) {
    if (previousLedger == null || reviewer == null || concern == null || concern.getId() == null) {
      return Optional.empty();
    }
    return reviewerConcerns(previousLedger, reviewer).getConcerns().stream()
        .filter(previous -> concern.getId().equals(previous.getId()))
        .map(ReviewConcern::getStatus)
        .findFirst();
  }

  private String reactivationHeading(ConcernStatus previousStatus) {
    return switch (previousStatus) {
      case FIXED -> "Regression of a previously fixed AI concern:";
      case DISMISSED -> "Previously dismissed AI concern is actionable again:";
      case SKIPPED -> "Previously skipped AI concern is actionable after review resumed:";
      case PRESENT, UNCERTAIN ->
          throw new IllegalArgumentException(
              "Cannot reactivate concern from " + previousStatus);
    };
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
        if (!isConcernWorthy(reply)) {
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

  public boolean isConcernWorthy(AiReplyItem reply) {
    boolean irrelevant =
        reply.getRelevance() != null
            && reply.getRelevance() < config.getFilterCommentsRelevanceThreshold();
    return !reply.isDuplicated() && !reply.isConflicting() && !irrelevant;
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

  private ReviewScope reviewScope(ConcernReviewerId reviewer, ReviewConcern concern) {
    if (reviewer == null || reviewer.getKind() == null) {
      return null;
    }
    return switch (reviewer.getKind()) {
      case SCOPED_AGENT -> scopedAgentScope(reviewer.getName());
      case SPECIALIZED_AGENT ->
          "COMMIT_MESSAGE".equals(reviewer.getName())
              ? ReviewScope.COMMIT_MESSAGE
              : ReviewScope.PATCHSET;
      case SINGLE_AGENT -> singleAgentScope(concern);
    };
  }

  private ReviewScope scopedAgentScope(String reviewerName) {
    if ("PATCHSET".equals(reviewerName)) {
      return ReviewScope.PATCHSET;
    }
    if ("COMMIT_MESSAGE".equals(reviewerName)) {
      return ReviewScope.COMMIT_MESSAGE;
    }
    return null;
  }

  private ReviewScope singleAgentScope(ReviewConcern concern) {
    boolean hasCommitMessageLocation = false;
    boolean hasPatchSetLocation = false;
    for (var location : concern.getLocations()) {
      String filename = location.getFilename();
      if (filename == null || filename.isBlank()) {
        continue;
      }
      if (filename.endsWith("/COMMIT_MSG")) {
        hasCommitMessageLocation = true;
      } else {
        hasPatchSetLocation = true;
      }
    }
    if (hasCommitMessageLocation == hasPatchSetLocation) {
      return null;
    }
    return hasCommitMessageLocation ? ReviewScope.COMMIT_MESSAGE : ReviewScope.PATCHSET;
  }

  private String skippedScopeReason(ReviewScope scope) {
    return switch (scope) {
      case PATCHSET -> "Patch-set review skipped because its scope is disabled.";
      case COMMIT_MESSAGE -> "Commit-message review skipped because its scope is disabled.";
      case FULL -> throw new IllegalArgumentException("The full review scope cannot be disabled");
    };
  }

  private boolean hasDisabledReviews(ReviewFeedbackMemory feedback) {
    return (feedback.getDisabledReviewScopes() != null
            && !feedback.getDisabledReviewScopes().isEmpty())
        || (feedback.getDisabledSpecializedAgents() != null
            && !feedback.getDisabledSpecializedAgents().isEmpty());
  }

  private String skippedReason(
      ReviewFeedbackMemory feedback, ConcernReviewerId reviewer, ReviewConcern concern) {
    ReviewScope scope = reviewScope(reviewer, concern);
    if (scope != null && feedback.isReviewScopeDisabled(scope)) {
      return skippedScopeReason(scope);
    }
    if (reviewer != null
        && reviewer.getKind() == ConcernReviewerId.Kind.SPECIALIZED_AGENT
        && feedback.getDisabledSpecializedAgents() != null
        && feedback.getDisabledSpecializedAgents().contains(reviewer.getName())) {
      return String.format(
          localizer.getText("message.review.concern.specialized.agent.skipped"),
          reviewer.getName());
    }
    return null;
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
