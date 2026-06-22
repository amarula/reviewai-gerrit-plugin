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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SpecializedReviewCollectorResultMerger {
  private SpecializedReviewCollectorResultMerger() {}

  static AiResponseContent merge(
      List<SpecializedReviewAgentReplies> specializedReplies,
      List<SpecializedReviewCollectorAgent> collectors,
      Map<ReviewAssistantStage, AiResponseContent> responses) {
    Map<Integer, AiReplyItem> repliesById = buildFinalReplies(specializedReplies);
    for (SpecializedReviewCollectorAgent collector : collectors) {
      Map<Integer, AiReplyItem> resultsById =
          validateCollectorResponse(
              collector.stage().name(), responses.get(collector.stage()), repliesById.keySet());
      for (Map.Entry<Integer, AiReplyItem> entry : repliesById.entrySet()) {
        collector.merge(entry.getValue(), resultsById.get(entry.getKey()));
      }
    }
    for (AiReplyItem reply : repliesById.values()) {
      reply.setConflicting(false);
      reply.setRepeatedReason(null);
      reply.setDuplicatedReason(null);
      reply.setConflictingReason(null);
    }
    AiResponseContent response = new AiResponseContent("");
    response.setReplies(new ArrayList<>(repliesById.values()));
    return response;
  }

  private static Map<Integer, AiReplyItem> buildFinalReplies(
      List<SpecializedReviewAgentReplies> specializedReplies) {
    Map<Integer, AiReplyItem> repliesById = new LinkedHashMap<>();
    for (SpecializedReviewAgentReplies agentReplies : specializedReplies) {
      for (SpecializedReviewAgentReplies.SpecializedReviewAgentReply source :
          agentReplies.getReplies()) {
        if (source.getId() == null || repliesById.containsKey(source.getId())) {
          throw new IllegalStateException("Missing or duplicate specialized reply ID");
        }
        repliesById.put(
            source.getId(),
            AiReplyItem.builder()
                .id(source.getId())
                .reply(source.getReply())
                .score(source.getScore())
                .sourceAgent(agentReplies.getAgent())
                .filename(source.getFilename())
                .lineNumber(source.getLineNumber())
                .codeSnippet(source.getCodeSnippet())
                .build());
      }
    }
    return repliesById;
  }

  private static Map<Integer, AiReplyItem> validateCollectorResponse(
      String collectorName, AiResponseContent response, Set<Integer> expectedIds) {
    if (response == null || response.getReplies() == null) {
      throw new IllegalStateException("Missing " + collectorName + " response");
    }
    Map<Integer, AiReplyItem> repliesById = new LinkedHashMap<>();
    for (AiReplyItem reply : response.getReplies()) {
      if (reply == null
          || reply.getId() == null
          || !expectedIds.contains(reply.getId())
          || repliesById.putIfAbsent(reply.getId(), reply) != null) {
        throw new IllegalStateException("Invalid reply ID from " + collectorName);
      }
    }
    if (!new HashSet<>(repliesById.keySet()).equals(expectedIds)) {
      throw new IllegalStateException("Incomplete " + collectorName + " response");
    }
    return repliesById;
  }
}
