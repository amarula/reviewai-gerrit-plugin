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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedDuplicationCollector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedRelevanceCollector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedRepetitionCollector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewCollector;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

public class SpecializedReviewCollectorsTest {
  @Test
  public void collectorPromptsHaveIsolatedResponsibilities() {
    String repetition = collectorInstructions(ReviewAssistantStage.REVIEW_SPECIALIZED_REPETITION_COLLECTOR);
    String duplication = collectorInstructions(ReviewAssistantStage.REVIEW_SPECIALIZED_DUPLICATION_COLLECTOR);
    String relevance = collectorInstructions(ReviewAssistantStage.REVIEW_SPECIALIZED_RELEVANCE_COLLECTOR);

    assertTrue(repetition.contains("repetition_reply_id"));
    assertTrue(repetition.contains("Do not compare new replies with each other"));
    assertFalse(repetition.contains("Assign `relevance`"));
    assertTrue(duplication.contains("Compare only the new replies"));
    assertTrue(duplication.contains("Do not evaluate historical repetition"));
    assertFalse(duplication.contains("repetition_reply_id"));
    assertTrue(relevance.contains("Assign `relevance` in the range [0, 1]"));
    assertTrue(relevance.contains("Do not evaluate repetition, duplication, or conflicts"));
  }

  @Test
  public void mergesThreeCollectorResultsByReplyId() throws Exception {
    RecordingCollectorClient client = new RecordingCollectorClient(config(AiProviderType.OPENAI));
    List<SpecializedReviewAgentReplies> sourceReplies = sourceReplies();

    AiResponseContent response =
        client.askCollector(new ChangeSetData(1, -1, 1), change(), sourceReplies);

    assertEquals(
        EnumSet.of(
            ReviewAssistantStage.REVIEW_SPECIALIZED_REPETITION_COLLECTOR,
            ReviewAssistantStage.REVIEW_SPECIALIZED_DUPLICATION_COLLECTOR,
            ReviewAssistantStage.REVIEW_SPECIALIZED_RELEVANCE_COLLECTOR),
        EnumSet.copyOf(client.stages));
    AiReplyItem first = response.getReplies().get(0);
    assertEquals(Integer.valueOf(100_000), first.getId());
    assertEquals("First finding", first.getReply());
    assertEquals("CORRECTNESS", first.getSourceAgent());
    assertTrue(first.isRepeated());
    assertEquals("past-comment-id", first.getRepetitionReplyId());
    assertFalse(first.isDuplicated());
    assertEquals(0.9, first.getRelevance(), 0.0);
    assertFalse(first.isConflicting());
    assertNull(first.getConflictingReason());

    AiReplyItem second = response.getReplies().get(1);
    assertFalse(second.isRepeated());
    assertNull(second.getRepetitionReplyId());
    assertTrue(second.isDuplicated());
    assertEquals(0.4, second.getRelevance(), 0.0);
  }

  @Test
  public void sendsExplicitHistoryOnlyToNonOpenAiRepetitionCollector() throws Exception {
    RecordingCollectorClient client = new RecordingCollectorClient(config(AiProviderType.GEMINI));
    client.history = List.of(historyEntry());

    client.askCollector(new ChangeSetData(1, -1, 1), change(), sourceReplies());

    assertEquals(1, client.repetitionHistorySize);
    assertEquals(0, client.otherCollectorHistorySizes.stream().mapToInt(Integer::intValue).sum());
  }

  @Test
  public void openAiRepetitionCollectorUsesConversationInsteadOfExplicitHistory() throws Exception {
    RecordingCollectorClient client = new RecordingCollectorClient(config(AiProviderType.OPENAI));
    client.history = List.of(historyEntry());

    client.askCollector(new ChangeSetData(1, -1, 1), change(), sourceReplies());

    assertEquals(0, client.repetitionHistorySize);
  }

  @Test
  public void openAiZdrRepetitionCollectorUsesExplicitHistory() throws Exception {
    RecordingCollectorClient client =
        new RecordingCollectorClient(config(AiProviderType.OPENAI, true));
    client.history = List.of(historyEntry());

    client.askCollector(new ChangeSetData(1, -1, 1), change(), sourceReplies());

    assertEquals(1, client.repetitionHistorySize);
  }

  @Test
  public void failsWholeReviewWhenOneCollectorFails() {
    RecordingCollectorClient client = new RecordingCollectorClient(config(AiProviderType.OPENAI));
    client.failingStage = ReviewAssistantStage.REVIEW_SPECIALIZED_DUPLICATION_COLLECTOR;

    assertThrows(
        IllegalStateException.class,
        () -> client.askCollector(new ChangeSetData(1, -1, 1), change(), sourceReplies()));
  }

  private static String collectorInstructions(ReviewAssistantStage stage) {
    ChangeSetData data = new ChangeSetData(1, -1, 1);
    data.setReviewAssistantStage(stage);
    AiPromptSpecializedReviewCollector prompt =
        switch (stage) {
          case REVIEW_SPECIALIZED_REPETITION_COLLECTOR ->
              new AiPromptSpecializedRepetitionCollector(
                  config(AiProviderType.OPENAI), data, change(), null);
          case REVIEW_SPECIALIZED_DUPLICATION_COLLECTOR ->
              new AiPromptSpecializedDuplicationCollector(
                  config(AiProviderType.OPENAI), data, change(), null);
          case REVIEW_SPECIALIZED_RELEVANCE_COLLECTOR ->
              new AiPromptSpecializedRelevanceCollector(
                  config(AiProviderType.OPENAI), data, change(), null);
          default -> throw new IllegalArgumentException("Not a collector stage: " + stage);
        };
    return prompt.getDefaultAiAssistantInstructions();
  }

  private static List<SpecializedReviewAgentReplies> sourceReplies() {
    List<SpecializedReviewAgentReplies> replies =
        List.of(
            SpecializedReviewAgentReplies.from(
                "CORRECTNESS",
                List.of(
                    AiReplyItem.builder().reply("First finding").score(-1.0).build(),
                    AiReplyItem.builder().reply("Second finding").score(-1.0).build())));
    SpecializedReviewReplyIdAssigner.assign(1, replies);
    return replies;
  }

  private static AiReviewHistoryInfo.Entry historyEntry() {
    return new AiReviewHistoryInfo.Entry(
        "past-comment-id",
        null,
        "assistant",
        false,
        "ReviewAI",
        "2026-06-22T10:00:00Z",
        1,
        "src/Test.java",
        42,
        null,
        "Past finding");
  }

  private static Configuration config(AiProviderType providerType) {
    return config(providerType, false);
  }

  private static Configuration config(AiProviderType providerType, boolean aiProviderZdr) {
    Configuration config = mock(Configuration.class);
    when(config.getAiProviderType()).thenReturn(providerType);
    when(config.getAiProviderZdr()).thenReturn(aiProviderZdr);
    return config;
  }

  private static GerritChange change() {
    GerritChange change = mock(GerritChange.class);
    when(change.getFullChangeId()).thenReturn("change~1");
    return change;
  }

  private static AiResponseContent response(AiReplyItem... replies) {
    AiResponseContent response = new AiResponseContent("");
    response.setReplies(List.of(replies));
    return response;
  }

  private static class RecordingCollectorClient extends LangChainSpecializedAgentReviewClient {
    private final List<ReviewAssistantStage> stages = new ArrayList<>();
    private final List<Integer> otherCollectorHistorySizes = new ArrayList<>();
    private List<AiReviewHistoryInfo.Entry> history = List.of();
    private int repetitionHistorySize;
    private ReviewAssistantStage failingStage;

    RecordingCollectorClient(Configuration config) {
      super(config, null, null, null, Runnable::run);
    }

    @Override
    protected List<AiReviewHistoryInfo.Entry> collectPastReviewReplies(
        ChangeSetData changeSetData, GerritChange change) {
      return history;
    }

    @Override
    protected AiResponseContent askCollectorStage(
        ChangeSetData changeSetData,
        GerritChange change,
        List<SpecializedReviewAgentReplies> specializedReplies,
        List<AiReviewHistoryInfo.Entry> pastReplies,
        ReviewAssistantStage stage) {
      stages.add(stage);
      if (stage == failingStage) {
        throw new IllegalStateException("collector failed");
      }
      if (stage == ReviewAssistantStage.REVIEW_SPECIALIZED_REPETITION_COLLECTOR) {
        repetitionHistorySize = pastReplies.size();
        return response(
            AiReplyItem.builder()
                .id(100_000)
                .repeated(true)
                .repetitionReplyId("past-comment-id")
                .build(),
            AiReplyItem.builder().id(100_001).repeated(false).build());
      }
      otherCollectorHistorySizes.add(pastReplies.size());
      if (stage == ReviewAssistantStage.REVIEW_SPECIALIZED_DUPLICATION_COLLECTOR) {
        return response(
            AiReplyItem.builder().id(100_000).duplicated(false).build(),
            AiReplyItem.builder().id(100_001).duplicated(true).build());
      }
      return response(
          AiReplyItem.builder().id(100_000).relevance(0.9).build(),
          AiReplyItem.builder().id(100_001).relevance(0.4).build());
    }
  }
}
