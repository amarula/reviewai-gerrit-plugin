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
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
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
  public void suggestWithoutScopeSingleAgentUsesOneUnifiedReviewRequest() throws Exception {
    RecordingLangChainClient client = new RecordingLangChainClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    String patchSet = readTestResource(SUGGEST_ORIGINAL_PATCH_SET_RESOURCE);

    AiResponseContent response = client.ask(changeSetData, change, patchSet);

    assertEquals(2, response.getReplies().size());
    assertEquals("a.py", response.getReplies().get(0).getFilename());
    assertEquals("/COMMIT_MSG", response.getReplies().get(1).getFilename());
    assertEquals(List.of(false, true), client.recordedSuggestModes);
    assertEquals(List.of(false, true), client.recordedForcedStagedReview);
    assertEquals(2, client.recordedPatchSets.size());
    assertTrue(client.recordedPatchSets.get(1).contains("Code review issue"));
    assertTrue(client.recordedPatchSets.get(1).contains("Commit message review issue"));
  }

  @Test
  public void suggestPatchsetScopeRequestsSuggestionsForEachReviewReply() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    client.reviewReplies =
        List.of(
            reviewReply("First issue", "a.py", 2, "return value.strip().lower()"),
            reviewReply("Second issue", "b.py", 4, "return fallback"));
    client.suggestionReplies =
        List.of(
            codeSuggestionReply(
                0,
                readTestResource(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE),
                "a.py",
                2,
                "return value.strip().lower()"),
            codeSuggestionReply(
                1,
                readTestResource(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE),
                "b.py",
                4,
                "return fallback"));
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
    assertEquals(2, response.getReplies().size());
    assertEquals(readTestResource(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE), response.getReplies().get(0).getReply());
    assertEquals("a.py", response.getReplies().get(0).getFilename());
    assertEquals(Integer.valueOf(2), response.getReplies().get(0).getLineNumber());
    assertEquals("return value.strip().lower()", response.getReplies().get(0).getCodeSnippet());
    assertEquals("b.py", response.getReplies().get(1).getFilename());
    response.getReplies().forEach(
        reply -> {
          assertNull(reply.getId());
          assertNull(reply.getScore());
        });
    assertEquals(
        List.of(
            ReviewAssistantStage.REVIEW_CODE,
            ReviewAssistantStage.REVIEW_CODE),
        client.recordedStages);
    assertEquals(List.of(false, true), client.recordedSuggestModes);
    assertEquals(patchSet, client.recordedPatchSets.get(0));
    assertTrue(client.recordedPatchSets.get(1).contains("First issue"));
    assertTrue(client.recordedPatchSets.get(1).contains("Second issue"));
  }

  @Test
  public void suggestIncludesRepeatedNegativeReviewInSingleSuggestionRequest() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    AiReplyItem repeatedNegative =
        reviewReply("Repeated but still negative", "a.py", 2, "return value");
    repeatedNegative.setRepeated(true);
    client.reviewReplies = List.of(repeatedNegative);
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.PATCHSET);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");

    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(SUGGEST_ORIGINAL_PATCH_SET_RESOURCE));

    assertEquals(1, response.getReplies().size());
    assertEquals(List.of(false, true), client.recordedSuggestModes);
    assertTrue(client.recordedPatchSets.get(1).contains("Repeated but still negative"));
  }

  @Test
  public void suggestResponseCanContainMultipleEditsForOneReviewReply() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    client.suggestionReplies =
        List.of(
            codeSuggestionReply(0, "```suggestion\nfirst replacement\n```"),
            codeSuggestionReply(0, "```suggestion\nsecond replacement\n```"));
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.PATCHSET);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(SUGGEST_ORIGINAL_PATCH_SET_RESOURCE));

    assertEquals(2, response.getReplies().size());
    assertEquals("a.py", response.getReplies().get(0).getFilename());
    assertEquals("a.py", response.getReplies().get(1).getFilename());
  }

  @Test
  public void suggestRejectsCodeEditWithoutItsOwnTarget() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    client.suggestionReplies =
        List.of(
            AiReplyItem.builder()
                .id(0)
                .reply(readTestResource(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE))
                .build());
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.PATCHSET);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");

    AiResponseContent response =
        client.ask(changeSetData, change, readTestResource(SUGGEST_ORIGINAL_PATCH_SET_RESOURCE));

    assertTrue(response.getReplies().isEmpty());
  }

  @Test
  public void suggestCommitMessageUsesCommitMessageInlineLocation() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.COMMIT_MESSAGE);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");
    String patchSet = readTestResource("__files/langchain/suggestOriginalPatchSetWithCommitMessage.txt");

    AiResponseContent response = client.ask(changeSetData, change, patchSet);

    assertEquals(1, response.getReplies().size());
    AiReplyItem suggestion = response.getReplies().get(0);
    assertEquals("/COMMIT_MSG", suggestion.getFilename());
    assertNull(suggestion.getLineNumber());
    assertTrue(suggestion.getCodeSnippet().contains("Minor fixes"));
    assertNull(suggestion.getId());
    assertTrue(client.recordedPatchSets.get(1).contains("Commit message review issue"));
    assertEquals(List.of(false, true), client.recordedSuggestModes);
  }

  @Test
  public void suggestPublishesOnlyOneAllInclusiveCommitMessageEdit() throws Exception {
    RecordingLangChainMultiAgentReviewClient client = new RecordingLangChainMultiAgentReviewClient();
    client.commitMessageReviewReplies =
        List.of(
            reviewReply("Clarify the subject", "ignored", 1, "ignored"),
            reviewReply("Explain the motivation", "ignored", 1, "ignored"));
    String firstSuggestion = "```suggestion\nAll-inclusive commit message\n```";
    client.suggestionReplies =
        List.of(
            commitMessageSuggestionReply(0, firstSuggestion),
            commitMessageSuggestionReply(1, "```suggestion\nSecond commit message\n```"));
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setSuggestMode(true);
    changeSetData.setReviewScope(ReviewScope.COMMIT_MESSAGE);
    GerritChange change = mock(GerritChange.class);
    when(change.getIsCommentEvent()).thenReturn(true);
    when(change.getFullChangeId()).thenReturn("change~1");

    AiResponseContent response =
        client.ask(
            changeSetData,
            change,
            readTestResource("__files/langchain/suggestOriginalPatchSetWithCommitMessage.txt"));

    assertEquals(1, response.getReplies().size());
    assertEquals(firstSuggestion, response.getReplies().getFirst().getReply());
    assertEquals("/COMMIT_MSG", response.getReplies().getFirst().getFilename());
    assertTrue(client.recordedPatchSets.get(1).contains("Clarify the subject"));
    assertTrue(client.recordedPatchSets.get(1).contains("Explain the motivation"));
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
    client.suggestionReplies =
        List.of(
            codeSuggestionReply(0, readTestResource(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE)),
            commitMessageSuggestionReply(
                1, readTestResource(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE)));
    String patchSet = readTestResource(SUGGEST_ORIGINAL_PATCH_SET_RESOURCE);

    AiResponseContent response = client.ask(changeSetData, change, patchSet);

    assertNotNull(response.getReplies());
    assertEquals(2, response.getReplies().size());
    assertEquals("a.py", response.getReplies().get(0).getFilename());
    assertEquals("/COMMIT_MSG", response.getReplies().get(1).getFilename());
    response.getReplies().forEach(
        reply -> {
          assertNull(reply.getId());
          assertNull(reply.getScore());
        });
    assertEquals(
        List.of(
            ReviewAssistantStage.REVIEW_CODE,
            ReviewAssistantStage.REVIEW_COMMIT_MESSAGE,
            ReviewAssistantStage.REVIEW_CODE,
            ReviewAssistantStage.REVIEW_COMMIT_MESSAGE),
        client.recordedStages);
    assertEquals(List.of(false, false, true, true), client.recordedSuggestModes);
    assertEquals(List.of(true, true, true, true), client.recordedForcedStagedReview);
    String patchsetReviewIssue = client.reviewReplies.getFirst().getReply();
    String commitMessageReviewIssue = client.commitMessageReviewReplies.getFirst().getReply();
    assertTrue(client.recordedPatchSets.get(2).contains(patchsetReviewIssue));
    assertFalse(client.recordedPatchSets.get(2).contains(commitMessageReviewIssue));
    assertFalse(client.recordedPatchSets.get(3).contains(patchsetReviewIssue));
    assertTrue(client.recordedPatchSets.get(3).contains(commitMessageReviewIssue));
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

  private static List<String> readTestResourceLines(String resourceName) throws Exception {
    return Files.readAllLines(TEST_RESOURCES_PATH.resolve(resourceName));
  }

  private static AiReplyItem reviewReply(
      String reply, String filename, int lineNumber, String codeSnippet) {
    return AiReplyItem.builder()
        .reply(reply)
        .filename(filename)
        .lineNumber(lineNumber)
        .codeSnippet(codeSnippet)
        .score(-1.0)
        .build();
  }

  private static AiReplyItem codeSuggestionReply(int id, String reply) {
    return codeSuggestionReply(id, reply, "a.py", 2, "return value.strip().lower()");
  }

  private static AiReplyItem codeSuggestionReply(
      int id, String reply, String filename, int lineNumber, String codeSnippet) {
    return AiReplyItem.builder()
        .id(id)
        .reply(reply)
        .filename(filename)
        .lineNumber(lineNumber)
        .codeSnippet(codeSnippet)
        .score(1.0)
        .build();
  }

  private static AiReplyItem commitMessageSuggestionReply(int id, String reply) {
    return AiReplyItem.builder().id(id).reply(reply).filename("/COMMIT_MSG").score(1.0).build();
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
    when(localizer.getText("plugin.message.prefix")).thenReturn("ReviewAI");
    when(localizer.getText("plugin.message.label")).thenReturn("Message");
    when(localizer.getText("plugin.warning.label")).thenReturn("**WARNING**");
    when(localizer.getText("plugin.error.label")).thenReturn("**ERROR**");
    when(localizer.getText("message.empty.review")).thenReturn("");
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
    private String patchSetSuggestion = readTestResourceUnchecked(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE);
    private List<AiReplyItem> reviewReplies =
        List.of(reviewReply("Review issue", "a.py", 2, "return value.strip().lower()"));
    private List<AiReplyItem> commitMessageReviewReplies =
        List.of(AiReplyItem.builder().reply("Commit message review issue").score(-1.0).build());
    private List<AiReplyItem> suggestionReplies = List.of();
    private ReviewAssistantStage routedStage = ReviewAssistantStage.REVIEW_CODE;
    private int routeCalls;

    RecordingLangChainMultiAgentReviewClient() {
      super(null, null, null, null, Runnable::run);
    }

    @Override
    protected ReviewRequestResult askSingleRequest(
        ChangeSetData changeSetData, GerritChange change, String patchSet) {
      ReviewAssistantStage stage = changeSetData.getReviewAssistantStage();
      recordedStages.add(stage);
      recordedForcedStagedReview.add(changeSetData.getForcedStagedReview());
      recordedSuggestModes.add(changeSetData.getSuggestMode());
      recordedPatchSets.add(patchSet);

      AiResponseContent response = new AiResponseContent("");
      if (changeSetData.getSuggestMode()) {
        List<AiReplyItem> replies =
            suggestionReplies.isEmpty()
                ? List.of(
                    changeSetData.getReviewScope() == ReviewScope.COMMIT_MESSAGE
                        ? commitMessageSuggestionReply(0, patchSetSuggestion)
                        : codeSuggestionReply(0, patchSetSuggestion))
                : suggestionReplies;
        response.setReplies(new ArrayList<>(replies));
      } else if (changeSetData.getForcedReview()) {
        response.setReplies(
            new ArrayList<>(
                stage == ReviewAssistantStage.REVIEW_COMMIT_MESSAGE
                    ? commitMessageReviewReplies
                    : reviewReplies));
      } else {
        response.setReplies(
            new ArrayList<>(List.of(AiReplyItem.builder().reply(stage.name()).build())));
      }

      return new ReviewRequestResult(response, "body-" + stage.name());
    }

    @Override
    protected ReviewAssistantStage routeMessage(ChangeSetData changeSetData, GerritChange change)
        throws AiConnectionFailException {
      routeCalls++;
      return routedStage;
    }
  }

  private static class RecordingLangChainClient extends LangChainClient {
    private final List<Boolean> recordedForcedStagedReview = new ArrayList<>();
    private final List<Boolean> recordedSuggestModes = new ArrayList<>();
    private final List<String> recordedPatchSets = new ArrayList<>();

    RecordingLangChainClient() {
      super(null, null, null, null);
    }

    @Override
    protected ReviewRequestResult askSingleRequest(
        ChangeSetData changeSetData, GerritChange change, String patchSet) {
      recordedForcedStagedReview.add(changeSetData.getForcedStagedReview());
      recordedSuggestModes.add(changeSetData.getSuggestMode());
      recordedPatchSets.add(patchSet);

      AiResponseContent response = new AiResponseContent("");
      response.setReplies(
          changeSetData.getSuggestMode()
              ? new ArrayList<>(
                  List.of(
                      codeSuggestionReply(
                          0, readTestResourceUnchecked(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE)),
                      commitMessageSuggestionReply(
                          1, readTestResourceUnchecked(SUGGEST_PATCH_SET_FIX_REPLY_RESOURCE))))
              : new ArrayList<>(
                  List.of(
                      reviewReply(
                          "Code review issue", "a.py", 2, "return value.strip().lower()"),
                      AiReplyItem.builder()
                          .reply("Commit message review issue")
                          .score(-1.0)
                          .build())));
      return new ReviewRequestResult(response, "body");
    }
  }
}
