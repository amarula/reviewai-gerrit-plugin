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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils;
import com.googlesource.gerrit.plugins.reviewai.web.ReviewAgentConversationStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.junit.Test;

public class LangChainMultiAgentReviewClientTest {
  private static final Path TEST_RESOURCES_PATH = Paths.get("src/test/resources");
  private static final String ROUTER_HISTORY_PROMPT_RESOURCE =
      "__files/langchain/routerAiDataPromptWithHistory.json";
  private static final String ROUTER_HISTORY_EXPECTED_MESSAGES_RESOURCE =
      "__files/langchain/routerAiDataPromptWithHistoryExpectedMessages.txt";
  private static final String ROUTER_AI_REVIEW_COMMENTS_RESOURCE =
      "__files/langchain/routerAiReviewComments.json";
  private static final String ROUTER_CONTEXT_WITH_AI_REVIEW_EXPECTED_MESSAGES_RESOURCE =
      "__files/langchain/routerContextWithAiReviewExpectedMessages.txt";
  private static final String ROUTER_CONTEXT_WITH_AUTOMATIC_REVIEW_EXPECTED_MESSAGES_RESOURCE =
      "__files/langchain/routerContextWithAutomaticReviewExpectedMessages.txt";
  private static final String SUGGEST_ORIGINAL_PATCH_SET_RESOURCE =
      "__files/langchain/suggestOriginalPatchSet.txt";
  private static final String SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE =
      "__files/langchain/suggestPatchSetFixReply.txt";
  private static final String SUGGEST_ORIGINAL_PATCH_SET_REVERTED_BY_FIX_RESOURCE =
      "__files/langchain/suggestOriginalPatchSetRevertedByFix.txt";
  private static final String SUGGEST_PATCH_SET_FIX_REVERTING_ORIGINAL_RESOURCE =
      "__files/langchain/suggestPatchSetFixRevertingOriginal.txt";
  private static final String SUGGEST_EMPTY_FINAL_PATCH_SET_MESSAGE_RESOURCE =
      "__files/langchain/suggestEmptyFinalPatchSetMessage.txt";
  private static final String SUGGEST_SYSTEM_MESSAGE_PREFIX_RESOURCE =
      "__files/langchain/suggestSystemMessagePrefix.txt";

  @Test
  public void mergesSeparatePatchsetAndCommitMessageReviews() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(false);
    when(change.getFullChangeId()).thenReturn("change~1");

    AiResponseContent response = client.ask(changeSetData, change, "patch");

    assertNotNull(response.getReplies());
    assertEquals(2, response.getReplies().size());
    assertEquals(
        List.of(ReviewAssistantStage.REVIEW_CODE, ReviewAssistantStage.REVIEW_COMMIT_MESSAGE),
        client.recordedStages);
    assertEquals(List.of(true, true), client.recordedForcedStagedReview);
    assertEquals("body-REVIEW_COMMIT_MESSAGE", client.getRequestBody());
  }

  @Test
  public void forcedScopedReviewBypassesParallelSplit() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setForcedStagedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(false);
    when(change.getFullChangeId()).thenReturn("change~1");

    AiResponseContent response = client.ask(changeSetData, change, "patch");

    assertNotNull(response.getReplies());
    assertEquals(1, response.getReplies().size());
    assertEquals(List.of(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE), client.recordedStages);
    assertEquals(List.of(true), client.recordedForcedStagedReview);
    assertEquals("body-REVIEW_COMMIT_MESSAGE", client.getRequestBody());
  }

  @Test
  public void forcedReviewCommentUsesPatchsetAndCommitMessageAgents() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setForcedReview(true);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");

    AiResponseContent response = client.ask(changeSetData, change, "patch");

    assertNotNull(response.getReplies());
    assertEquals(2, response.getReplies().size());
    assertEquals(
        List.of(ReviewAssistantStage.REVIEW_CODE, ReviewAssistantStage.REVIEW_COMMIT_MESSAGE),
        client.recordedStages);
    assertEquals(List.of(true, true), client.recordedForcedStagedReview);
  }

  @Test
  public void messageUsesRoutingAgentToSelectCommitMessageAgent() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    client.routedStage = ReviewAssistantStage.REVIEW_COMMIT_MESSAGE;
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");

    AiResponseContent response = client.ask(changeSetData, change, "patch");

    assertNotNull(response.getReplies());
    assertEquals(1, response.getReplies().size());
    assertEquals(1, client.routeCalls);
    assertEquals(List.of(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE), client.recordedStages);
    assertEquals(List.of(true), client.recordedForcedStagedReview);
    assertEquals("body-REVIEW_COMMIT_MESSAGE", client.getRequestBody());
  }

  @Test
  public void suggestPatchsetScopeRunsReviewSuggestionAndValidation() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.PATCHSET);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    client.patchSetSuggestion = readTestResource(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE);
    String patchSet = readTestResource(SUGGEST_ORIGINAL_PATCH_SET_RESOURCE);

    AiResponseContent response = client.ask(changeSetData, change, patchSet);

    assertNotNull(response.getReplies());
    assertEquals(1, response.getReplies().size());
    assertTrue(response.getReplies().get(0).getReply().contains("return value.strip().casefold()"));
    assertTrue(response.getReplies().get(0).getReply().contains("Final patchset:"));
    assertNull(response.getReplies().get(0).getScore());
    assertEquals(
        List.of(
            ReviewAssistantStage.REVIEW_CODE,
            ReviewAssistantStage.REVIEW_CODE,
            ReviewAssistantStage.REVIEW_CODE),
        client.recordedStages);
    assertEquals(List.of(false, true, false), client.recordedSuggestModes);
    assertEquals(patchSet, client.recordedPatchSets.get(0));
    assertEquals(patchSet, client.recordedPatchSets.get(1));
    assertTrue(client.recordedPatchSets.get(2).contains("return value.strip().casefold()"));
    assertEquals(1, countOccurrences(client.recordedPatchSets.get(2), "diff --git a/a.py b/a.py"));
    String finalPatchSet = extractFinalPatchSet(response.getReplies().get(0).getReply());
    assertTrue(finalPatchSet.startsWith("diff --git a/a.py b/a.py"));
    assertFalse(finalPatchSet.contains("Subject:"));
    assertFalse(finalPatchSet.contains("Change-Id:"));
  }

  @Test
  public void suggestPatchsetScopeDoesNotValidateEmptyFinalPatchset() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.PATCHSET);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    client.patchSetSuggestion = readTestResource(SUGGEST_PATCH_SET_FIX_REVERTING_ORIGINAL_RESOURCE);
    String patchSet = readTestResource(SUGGEST_ORIGINAL_PATCH_SET_REVERTED_BY_FIX_RESOURCE);

    AiResponseContent response = client.ask(changeSetData, change, patchSet);

    assertNotNull(response.getReplies());
    assertEquals(1, response.getReplies().size());
    String reply = response.getReplies().get(0).getReply();
    assertEquals(emptyFinalPatchSetResponse(), reply);
    assertFalse(reply.contains("Final patchset:"));
    assertFalse(reply.contains("Suggested patchset fix:"));
    assertFalse(reply.contains("Suggested commit message:"));
    assertNull(response.getReplies().get(0).getScore());
    assertEquals(
        List.of(ReviewAssistantStage.REVIEW_CODE, ReviewAssistantStage.REVIEW_CODE),
        client.recordedStages);
    assertEquals(List.of(false, true), client.recordedSuggestModes);
    assertEquals(List.of(patchSet, patchSet), client.recordedPatchSets);
  }

  @Test
  public void suggestWithoutScopeStopsWhenPatchsetIsUnamendable() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    client.patchSetSuggestion = readTestResource(SUGGEST_PATCH_SET_FIX_REVERTING_ORIGINAL_RESOURCE);

    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(SUGGEST_ORIGINAL_PATCH_SET_REVERTED_BY_FIX_RESOURCE));

    assertNotNull(response.getReplies());
    assertEquals(1, response.getReplies().size());
    String reply = response.getReplies().get(0).getReply();
    assertEquals(emptyFinalPatchSetResponse(), reply);
    assertFalse(reply.contains("Suggested patchset fix:"));
    assertFalse(reply.contains("Suggested commit message:"));
    assertEquals(
        List.of(ReviewAssistantStage.REVIEW_CODE, ReviewAssistantStage.REVIEW_CODE),
        client.recordedStages);
    assertEquals(List.of(false, true), client.recordedSuggestModes);
  }

  @Test
  public void suggestWithoutScopeProcessesPatchsetAndCommitMessage() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    client.patchSetSuggestion = readTestResource(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE);
    String patchSet = readTestResource(SUGGEST_ORIGINAL_PATCH_SET_RESOURCE);

    AiResponseContent response = client.ask(changeSetData, change, patchSet);

    assertNotNull(response.getReplies());
    assertEquals(2, response.getReplies().size());
    assertTrue(response.getReplies().get(0).getReply().contains("return value.strip().casefold()"));
    assertTrue(response.getReplies().get(0).getReply().contains("Final patchset:"));
    assertEquals("suggestion-REVIEW_COMMIT_MESSAGE", response.getReplies().get(1).getReply());
    String finalPatchSet = extractFinalPatchSet(response.getReplies().get(0).getReply());
    assertTrue(finalPatchSet.startsWith("diff --git a/a.py b/a.py"));
    assertFalse(finalPatchSet.contains("Subject:"));
    assertFalse(finalPatchSet.contains("Change-Id:"));
    response.getReplies().forEach(reply -> assertNull(reply.getScore()));
    assertEquals(
        List.of(
            ReviewAssistantStage.REVIEW_CODE,
            ReviewAssistantStage.REVIEW_CODE,
            ReviewAssistantStage.REVIEW_CODE,
            ReviewAssistantStage.REVIEW_COMMIT_MESSAGE,
            ReviewAssistantStage.REVIEW_COMMIT_MESSAGE,
            ReviewAssistantStage.REVIEW_COMMIT_MESSAGE),
        client.recordedStages);
    assertEquals(List.of(false, true, false, false, true, false), client.recordedSuggestModes);
  }

  @Test
  public void suggestReturnsLatestSuggestionAfterValidationAttemptsAreExhausted() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    client.validationScore = -1.0;
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.PATCHSET);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    client.patchSetSuggestion = readTestResource(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE);

    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(SUGGEST_ORIGINAL_PATCH_SET_RESOURCE));

    assertNotNull(response.getReplies());
    assertEquals(1, response.getReplies().size());
    assertTrue(response.getReplies().get(0).getReply().contains("Suggested patchset fix:"));
    assertTrue(response.getReplies().get(0).getReply().contains("Final patchset:"));
    assertNull(response.getReplies().get(0).getScore());
    assertEquals(7, client.recordedStages.size());
    String firstCandidateReviewPatchSet = client.recordedPatchSets.get(2);
    String secondSuggestionPatchSet = client.recordedPatchSets.get(3);
    assertTrue(firstCandidateReviewPatchSet.contains("return value.strip().casefold()"));
    assertTrue(secondSuggestionPatchSet.startsWith(firstCandidateReviewPatchSet));
    assertTrue(
        secondSuggestionPatchSet.contains(
            "Previous review of the merged candidate patchset to address:"));
    assertFalse(secondSuggestionPatchSet.contains("+    return value.strip().lower()"));
    assertFalse(
        client
            .recordedPatchSets
            .get(4)
            .contains("Previous review of the merged candidate patchset to address:"));
  }

  @Test
  public void suggestSkipsInitialReviewWhenReviewConversationExists() throws Exception {
    RecordingLangChainMultiAgentReviewClient client =
        new RecordingLangChainMultiAgentReviewClient(ReviewAssistantStage.REVIEW_CODE);
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.PATCHSET);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    client.patchSetSuggestion = readTestResource(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE);

    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(SUGGEST_ORIGINAL_PATCH_SET_RESOURCE));

    assertNotNull(response.getReplies());
    assertEquals(1, response.getReplies().size());
    assertEquals(
        List.of(ReviewAssistantStage.REVIEW_CODE, ReviewAssistantStage.REVIEW_CODE),
        client.recordedStages);
    assertEquals(List.of(true, false), client.recordedSuggestModes);
    assertTrue(client.recordedPatchSets.get(1).contains("return value.strip().casefold()"));
  }

  @Test
  public void routingHistoryIncludesUserAndAiMessagesFromRequestData() throws Exception {
    TestableLangChainMultiAgentReviewClient client = new TestableLangChainMultiAgentReviewClient();
    String requestData = readTestResource(ROUTER_HISTORY_PROMPT_RESOURCE);

    List<String> messages = summarizeMessages(client.buildRoutingHistoryMessages(requestData));

    assertEquals(readTestResourceLines(ROUTER_HISTORY_EXPECTED_MESSAGES_RESOURCE), messages);
  }

  @Test
  public void routingContextIncludesPreviousAiReviews() throws Exception {
    Configuration config = config();
    GerritClient gerritClient = mock(GerritClient.class);
    Localizer localizer = localizer();
    TestableLangChainMultiAgentReviewClient client =
        new TestableLangChainMultiAgentReviewClient(config, gerritClient, localizer);
    ChangeSetData changeSetData = new ChangeSetData(7, -1, 1);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(gerritClient.getClientData(change))
        .thenReturn(
            new GerritClientData(
                null,
                readCommentsResource(ROUTER_AI_REVIEW_COMMENTS_RESOURCE),
                new CommentData(List.of(), new HashMap<>(), new HashMap<>()),
                0));
    String requestData = readTestResource(ROUTER_HISTORY_PROMPT_RESOURCE);

    List<String> messages =
        summarizeMessages(client.buildRoutingContextMessages(changeSetData, change, requestData));

    assertEquals(
        readTestResourceLines(ROUTER_CONTEXT_WITH_AI_REVIEW_EXPECTED_MESSAGES_RESOURCE), messages);
  }

  @Test
  public void routingContextIncludesPatchsetCommitTriggeredReviews() throws Exception {
    Configuration config = config();
    GerritClient gerritClient = mock(GerritClient.class);
    ReviewAgentConversationStore conversationStore = mock(ReviewAgentConversationStore.class);
    Localizer localizer = localizer();
    TestableLangChainMultiAgentReviewClient client =
        new TestableLangChainMultiAgentReviewClient(
            config, gerritClient, localizer, conversationStore);
    ChangeSetData changeSetData = new ChangeSetData(7, -1, 1);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    when(conversationStore.getAutomaticReviewResponseTexts("change~1"))
        .thenReturn(
            List.of("Patchset-triggered review: commit message should mention null handling."));
    when(gerritClient.getClientData(change))
        .thenReturn(
            new GerritClientData(
                null,
                readCommentsResource(ROUTER_AI_REVIEW_COMMENTS_RESOURCE),
                new CommentData(List.of(), new HashMap<>(), new HashMap<>()),
                0));
    String requestData = readTestResource(ROUTER_HISTORY_PROMPT_RESOURCE);

    List<String> messages =
        summarizeMessages(client.buildRoutingContextMessages(changeSetData, change, requestData));

    assertEquals(
        readTestResourceLines(ROUTER_CONTEXT_WITH_AUTOMATIC_REVIEW_EXPECTED_MESSAGES_RESOURCE),
        messages);
  }

  private static List<String> summarizeMessages(List<ChatMessage> messages) {
    return messages.stream()
        .map(message -> message.type() + ":" + messageText(message))
        .toList();
  }

  private static String messageText(ChatMessage message) {
    if (message instanceof UserMessage userMessage) {
      return userMessage.singleText();
    }
    if (message instanceof AiMessage aiMessage) {
      return aiMessage.text();
    }
    if (message instanceof SystemMessage systemMessage) {
      return systemMessage.text();
    }
    return message.toString();
  }

  private static String readTestResource(String resourceName) throws Exception {
    return Files.readString(TEST_RESOURCES_PATH.resolve(resourceName));
  }

  private static String readTestResourceUnchecked(String resourceName) {
    try {
      return readTestResource(resourceName);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static String emptyFinalPatchSetResponse() throws Exception {
    return systemMessagePrefix() + ' ' + emptyFinalPatchSetMessage();
  }

  private static String systemMessagePrefix() throws Exception {
    return readTestResource(SUGGEST_SYSTEM_MESSAGE_PREFIX_RESOURCE).stripTrailing();
  }

  private static String emptyFinalPatchSetMessage() throws Exception {
    return readTestResource(SUGGEST_EMPTY_FINAL_PATCH_SET_MESSAGE_RESOURCE).stripTrailing();
  }

  private static List<String> readTestResourceLines(String resourceName) throws Exception {
    return Files.readAllLines(TEST_RESOURCES_PATH.resolve(resourceName));
  }

  private static int countOccurrences(String text, String value) {
    int count = 0;
    int index = text.indexOf(value);
    while (index >= 0) {
      count++;
      index = text.indexOf(value, index + value.length());
    }
    return count;
  }

  private static String extractFinalPatchSet(String reply) {
    String finalPatchSetPrefix = "Final patchset:\n```diff\n";
    int finalPatchSetStart = reply.indexOf(finalPatchSetPrefix);
    if (finalPatchSetStart < 0) {
      return "";
    }
    String finalPatchSet = reply.substring(finalPatchSetStart + finalPatchSetPrefix.length());
    int finalPatchSetEnd = finalPatchSet.lastIndexOf("\n```");
    if (finalPatchSetEnd < 0) {
      return finalPatchSet.strip();
    }
    return finalPatchSet.substring(0, finalPatchSetEnd).strip();
  }

  private static List<GerritComment> readCommentsResource(String resourceName) throws Exception {
    return List.of(
        GsonUtils.getGson().fromJson(readTestResource(resourceName), GerritComment[].class));
  }

  private static Configuration config() {
    Configuration config = mock(Configuration.class);
    when(config.getGerritUserName()).thenReturn("reviewai");
    when(config.getGerritUserEmail()).thenReturn("");
    when(config.getIgnoreResolvedAiComments()).thenReturn(false);
    when(config.getIgnoreOutdatedInlineComments()).thenReturn(false);
    return config;
  }

  private static Localizer localizer() {
    Localizer localizer = mock(Localizer.class);
    when(localizer.getText("system.message.prefix"))
        .thenReturn(readTestResourceUnchecked(SUGGEST_SYSTEM_MESSAGE_PREFIX_RESOURCE).stripTrailing());
    when(localizer.getText("message.empty.review")).thenReturn("");
    when(localizer.getText("message.suggest.patchset.unamendable"))
        .thenReturn(
            readTestResourceUnchecked(SUGGEST_EMPTY_FINAL_PATCH_SET_MESSAGE_RESOURCE)
                .stripTrailing());
    return localizer;
  }

  private static class TestableLangChainMultiAgentReviewClient
      extends LangChainMultiAgentReviewClient {
    TestableLangChainMultiAgentReviewClient() {
      super(null, null, null, null, Runnable::run);
    }

    TestableLangChainMultiAgentReviewClient(
        Configuration config, GerritClient gerritClient, Localizer localizer) {
      super(config, null, gerritClient, localizer, Runnable::run);
    }

    TestableLangChainMultiAgentReviewClient(
        Configuration config,
        GerritClient gerritClient,
        Localizer localizer,
        ReviewAgentConversationStore conversationStore) {
      super(config, null, gerritClient, localizer, conversationStore, Runnable::run);
    }
  }

  private static class RecordingLangChainMultiAgentReviewClient
      extends LangChainMultiAgentReviewClient {
    private final List<ReviewAssistantStage> recordedStages = new ArrayList<>();
    private final List<Boolean> recordedForcedStagedReview = new ArrayList<>();
    private final List<Boolean> recordedSuggestModes = new ArrayList<>();
    private final List<String> recordedPatchSets = new ArrayList<>();
    private String patchSetSuggestion;
    private ReviewAssistantStage routedStage = ReviewAssistantStage.REVIEW_CODE;
    private Double validationScore = 1.0;
    private int routeCalls;

    RecordingLangChainMultiAgentReviewClient() {
      this(new ArrayList<>());
    }

    RecordingLangChainMultiAgentReviewClient(ReviewAssistantStage existingReviewStage) {
      this(new ArrayList<>(List.of(existingReviewStage)));
    }

    RecordingLangChainMultiAgentReviewClient(List<ReviewAssistantStage> existingReviewStages) {
      super(
          recordingConfig(),
          null,
          null,
          localizer(),
          pluginDataProvider(existingReviewStages),
          Runnable::run);
    }

    @Override
    protected ReviewRequestResult askSingleRequest(
        ChangeSetData changeSetData, GerritChange change, String patchSet) {
      ReviewAssistantStage stage = changeSetData.getReviewAssistantStage();
      recordedStages.add(stage);
      recordedForcedStagedReview.add(changeSetData.getForcedStagedReview());
      recordedSuggestModes.add(changeSetData.getSuggestMode());
      recordedPatchSets.add(patchSet);

      String replyText = stage.name();
      Double score = null;
      if (!changeSetData.getSuggestMode() && patchSet.contains("suggestion-")) {
        replyText = "suggestion-valid";
        score = validationScore;
      } else if (!changeSetData.getSuggestMode() && patchSet.contains("casefold()")) {
        replyText = "suggestion-valid";
        score = validationScore;
      } else if (changeSetData.getSuggestMode()) {
        replyText =
            stage == ReviewAssistantStage.REVIEW_CODE && patchSetSuggestion != null
                ? patchSetSuggestion
                : "suggestion-" + stage.name();
        score = 1.0;
      }
      AiReplyItem reply = AiReplyItem.builder().reply(replyText).score(score).build();
      AiResponseContent response = new AiResponseContent("");
      response.setReplies(new ArrayList<>(List.of(reply)));

      return new ReviewRequestResult(response, "body-" + stage.name());
    }

    @Override
    protected ReviewAssistantStage routeMessage(ChangeSetData changeSetData, GerritChange change)
        throws AiConnectionFailException {
      routeCalls++;
      return routedStage;
    }

    private static Configuration recordingConfig() {
      Configuration config = mock(Configuration.class);
      when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
      return config;
    }

    private static PluginDataHandlerProvider pluginDataProvider(
        List<ReviewAssistantStage> existingReviewStages) {
      PluginDataHandler handler = mock(PluginDataHandler.class);
      for (ReviewAssistantStage stage : existingReviewStages) {
        when(handler.getValue("conversationId." + stage.name().toLowerCase(Locale.ROOT)))
            .thenReturn("conv-" + stage.name());
      }
      PluginDataHandlerProvider provider = mock(PluginDataHandlerProvider.class);
      when(provider.getChangeScope()).thenReturn(handler);
      return provider;
    }
  }
}
