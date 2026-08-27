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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai.ReviewConcernLedgerOperations;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinition;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class SpecializedReviewConcernLedgerOperations {
  private final ReviewConcernLedgerOperations ledgerOperations;

  SpecializedReviewConcernLedgerOperations(ReviewConcernLedgerOperations ledgerOperations) {
    this.ledgerOperations = ledgerOperations;
  }

  ReviewConcernLedger verifiedUpdates(
      AiResponseContent response,
      SpecializedReviewFindings verificationCandidates,
      List<SpecializedReviewFindings.AgentFindings> rawFindings) {
    ReviewConcernLedger updates = new ReviewConcernLedger();
    if (response == null
        || response.getReplies() == null
        || verificationCandidates == null) {
      return updates;
    }

    verificationCandidates.normalize();
    Map<String, Set<String>> agentsByRawConcernId = agentsByRawConcernId(rawFindings);
    Map<ConcernReviewerId, List<ReviewConcern>> concernsByReviewer = new LinkedHashMap<>();
    List<AiReplyItem> replies = response.getReplies();
    for (int i = 0; i < replies.size(); i++) {
      AiReplyItem reply = replies.get(i);
      if (reply == null || reply.getReply() == null || reply.getReply().isBlank()) {
        continue;
      }
      if (!ledgerOperations.isConcernWorthy(reply)) {
        continue;
      }
      Optional<ReviewConcern> matchedConcern =
          SpecializedReviewRepetitionMerger.matchedConcernForReply(
              reply, verificationCandidates.getConcerns(), i, replies.size());
      if (matchedConcern.isEmpty()) {
        log.warn("Unable to associate a verified reply with a specialized concern");
        continue;
      }

      ReviewConcern verifiedConcern = matchedConcern.get();
      List<String> sourceAgents =
          SpecializedReviewConcernIds.rawConcernIds(verifiedConcern).stream()
              .flatMap(id -> agentsByRawConcernId.getOrDefault(id, Set.of()).stream())
              .distinct()
              .toList();
      if (sourceAgents.isEmpty()) {
        log.warn(
            "Unable to associate verified concern {} with a specialized agent",
            verifiedConcern.getId());
        continue;
      }

      List<ConcernReviewerId> reviewers =
          sourceAgents.stream()
              .map(
                  agent ->
                      new ConcernReviewerId(
                          ConcernReviewerId.Kind.SPECIALIZED_AGENT, agent))
              .toList();
      Optional<String> owner =
          SpecializedReviewConcernOwnership.canonicalOwner(
              verifiedConcern.getOwnerAgent());
      if (owner.isEmpty()) {
        throw new IllegalStateException(
            "Verified specialized concern has no valid owner_agent");
      }
      ReviewConcern ledgerConcern = verifiedLedgerConcern(verifiedConcern, reply, reviewers);
      reply.setConcernId(ledgerConcern.getId());
      ConcernReviewerId reviewer =
          new ConcernReviewerId(
              ConcernReviewerId.Kind.SPECIALIZED_AGENT, owner.get());
      List<ReviewConcern> reviewerUpdates =
          concernsByReviewer.computeIfAbsent(reviewer, unused -> new ArrayList<>());
      if (reviewerUpdates.stream()
          .noneMatch(concern -> ledgerConcern.getId().equals(concern.getId()))) {
        reviewerUpdates.add(ledgerConcern);
      }
    }

    updates.setReviewers(
        concernsByReviewer.entrySet().stream()
            .map(entry -> reviewerConcerns(entry.getKey(), entry.getValue()))
            .toList());
    return updates;
  }

  AiResponseContent completeFollowUp(
      AiResponseContent response,
      GerritChange change,
      ReviewConcernLedger previousLedger,
      List<AgentFollowUp> followUps,
      ReviewConcernLedger newConcernUpdates) {
    Map<ConcernReviewerId, ReviewerConcerns> newConcernsByReviewer =
        newConcernUpdates.getReviewers().stream()
            .collect(
                Collectors.toMap(
                    ReviewerConcerns::getReviewer,
                    concerns -> concerns,
                    (left, right) -> left,
                    LinkedHashMap::new));
    List<ReviewerConcerns> reviewerUpdates = new ArrayList<>();
    for (AgentFollowUp followUp : followUps) {
      ReviewerConcerns reviewed = followUp.reviewedConcerns();
      List<ReviewConcern> combined = new ArrayList<>(reviewed.getConcerns());
      ReviewerConcerns newConcerns = newConcernsByReviewer.remove(reviewed.getReviewer());
      if (newConcerns != null) {
        Set<String> existingIds =
            combined.stream().map(ReviewConcern::getId).collect(Collectors.toSet());
        newConcerns.getConcerns().stream()
            .filter(concern -> !existingIds.contains(concern.getId()))
            .forEach(combined::add);
      }
      if (!combined.isEmpty()) {
        reviewerUpdates.add(reviewerConcerns(reviewed.getReviewer(), combined));
      }
    }
    reviewerUpdates.addAll(newConcernsByReviewer.values());

    ReviewConcernLedger updates = new ReviewConcernLedger();
    updates.setReviewers(reviewerUpdates);
    ReviewConcernLedger ledger = ledgerOperations.mergeReviewerUpdates(previousLedger, updates);

    Set<String> repeatedConcernIds = new LinkedHashSet<>();
    List<AiReplyItem> replies = new ArrayList<>();
    for (AgentFollowUp followUp : followUps) {
      ReviewerConcerns reviewed = followUp.reviewedConcerns();
      for (ReviewConcern concern : reviewed.getConcerns()) {
        if (concern.getStatus() != ConcernStatus.PRESENT
            || !repeatedConcernIds.add(concern.getId())) {
          continue;
        }
        replies.add(
            ledgerOperations.toPresentReply(
                previousLedger, reviewed.getReviewer(), concern));
      }
    }
    replies.addAll(response.getReplies());
    response.setReplies(replies);
    ledgerOperations.attachPendingLedger(response, change, ledger);
    return response;
  }

  AiResponseContent nonNullResponse(AiResponseContent response) {
    AiResponseContent nonNull = response == null ? new AiResponseContent("") : response;
    if (nonNull.getReplies() == null) {
      nonNull.setReplies(List.of());
    }
    return nonNull;
  }

  void normalizeOwnership(ReviewConcernLedger ledger) {
    SpecializedReviewConcernOwnership.normalizeLedger(ledger);
  }

  private Map<String, Set<String>> agentsByRawConcernId(
      List<SpecializedReviewFindings.AgentFindings> rawFindings) {
    Map<String, Set<String>> agentsByConcernId = new LinkedHashMap<>();
    for (SpecializedReviewFindings.AgentFindings agentFindings : rawFindings) {
      String agent = normalizedAgentName(agentFindings.getAgent());
      for (ReviewConcern concern : agentFindings.getConcerns()) {
        for (String concernId : SpecializedReviewConcernIds.rawConcernIds(concern)) {
          agentsByConcernId
              .computeIfAbsent(concernId, unused -> new LinkedHashSet<>())
              .add(agent);
        }
      }
    }
    return agentsByConcernId;
  }

  private ReviewConcern verifiedLedgerConcern(
      ReviewConcern verifiedConcern,
      AiReplyItem reply,
      List<ConcernReviewerId> reviewers) {
    ReviewConcern ledgerConcern = SpecializedReviewConcernIds.copyConcern(verifiedConcern);
    List<String> rawConcernIds = SpecializedReviewConcernIds.rawConcernIds(verifiedConcern);
    if (ledgerConcern.getId() == null || ledgerConcern.getId().isBlank()) {
      ledgerConcern.setId(rawConcernIds.isEmpty() ? null : "c-" + rawConcernIds.getFirst());
    }
    ledgerConcern.setStatus(ConcernStatus.PRESENT);
    ledgerConcern.setReviewers(reviewers);
    ledgerConcern.setReply(reply.getReply());
    ledgerConcern.setScore(reply.getScore());
    ledgerConcern.setRelevance(reply.getRelevance());
    ledgerConcern.setRepeated(reply.isRepeated());
    ledgerConcern.setRepeatedReason(reply.getRepeatedReason());
    ledgerConcern.setPreviousCommentId(reply.getRepetitionReplyId());
    if (ledgerConcern.getLocations().isEmpty() && hasLocation(reply)) {
      ledgerConcern.setLocations(List.of(locationFrom(reply)));
    }
    return ledgerConcern;
  }

  private ReviewerConcerns reviewerConcerns(
      ConcernReviewerId reviewer, List<ReviewConcern> concerns) {
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setReviewer(reviewer);
    reviewerConcerns.setConcerns(concerns);
    return reviewerConcerns;
  }

  private boolean hasLocation(AiReplyItem reply) {
    return reply.getFilename() != null
        || reply.getLineNumber() != null
        || reply.getCodeSnippet() != null;
  }

  private ConcernLocation locationFrom(AiReplyItem reply) {
    ConcernLocation location = new ConcernLocation();
    location.setFilename(reply.getFilename());
    location.setLineNumber(reply.getLineNumber());
    location.setCodeSnippet(reply.getCodeSnippet());
    return location;
  }

  private static String normalizedAgentName(String agent) {
    return SpecializedReviewAgentDefinition.normalizeName(agent);
  }

  record AgentFollowUp(
      SpecializedReviewFindings.AgentFindings findings,
      ReviewerConcerns reviewedConcerns) {}
}
