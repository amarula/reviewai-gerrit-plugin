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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;

import static com.googlesource.gerrit.plugins.reviewai.settings.Settings.GERRIT_PATCH_SET_FILENAME;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiRequestMessage;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.CommentData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.*;

public class AiHistoryTest {
  private static final int AI_ACCOUNT_ID = 7;
  private static final Path TEST_RESOURCES_PATH = TestResourceLoader.getTestResourcePath();
  private static final String FIXTURE_PATH = "__files/aibackend/common/client/prompt/";

  @Test
  public void patchSetHistoryStartsAfterLatestForgetThreadCommand() throws Exception {
    AiHistoryFixture fixture =
        readFixture("patchSetHistoryStartsAfterLatestForgetThreadCommand.json");
    HashMap<String, GerritComment> patchSetCommentMap = mapById(fixture.patchSetComments);

    AiHistory aiHistory =
        new AiHistory(
            config(),
            new ChangeSetData(AI_ACCOUNT_ID),
            new GerritClientData(
                null,
                List.of(),
                new CommentData(List.of(), new HashMap<>(), patchSetCommentMap),
                0),
            localizer());

    GerritComment patchSetMarker = new GerritComment();
    patchSetMarker.setFilename(GERRIT_PATCH_SET_FILENAME);

    assertEquals(
        List.of("user:second question", "assistant:second answer"),
        historySummary(aiHistory.retrieveHistory(patchSetMarker)));
  }

  @Test
  public void inlineThreadHistoryDropsMessagesBeforeForgetThreadCutoff() throws Exception {
    AiHistoryFixture fixture =
        readFixture("inlineThreadHistoryDropsMessagesBeforeForgetThreadCutoff.json");
    HashMap<String, GerritComment> commentMap = mapById(fixture.inlineComments);
    HashMap<String, GerritComment> patchSetCommentMap = mapById(fixture.patchSetComments);
    GerritComment currentComment = commentMap.get(fixture.currentCommentId);
    assertNotNull(currentComment);

    AiHistory aiHistory =
        new AiHistory(
            config(),
            new ChangeSetData(AI_ACCOUNT_ID),
            new GerritClientData(
                null,
                List.of(),
                new CommentData(List.of(), commentMap, patchSetCommentMap),
                0),
            localizer());

    assertEquals(
        List.of(
            "user:new question", "assistant:new answer", "user:final follow-up"),
        historySummary(aiHistory.retrieveHistory(currentComment)));
  }

  @Test
  public void nonAiDiscussionHistoryExcludesAiConversationMessages() throws Exception {
    AiHistoryFixture fixture =
        readFixture("nonAiDiscussionHistoryExcludesAiConversationMessages.json");
    HashMap<String, GerritComment> commentMap = mapById(fixture.inlineComments);
    GerritComment currentComment = commentMap.get(fixture.currentCommentId);
    assertNotNull(currentComment);

    AiHistory aiHistory =
        new AiHistory(
            config(),
            new ChangeSetData(AI_ACCOUNT_ID),
            new GerritClientData(
                null,
                List.of(),
                new CommentData(List.of(), commentMap, new HashMap<>()),
                0),
            localizer());

    assertEquals(
        List.of("user:This method needs tests."),
        historySummary(aiHistory.retrieveNonAiConversationHistory(currentComment)));
  }

  @Test
  public void patchSetHistoryDropsReviewContextNoise() {
    HashMap<String, GerritComment> patchSetCommentMap =
        mapById(
            List.of(
                patchSetComment("noise-1", "DYNAMIC CONFIGURATION SETTINGS\n\nmultiAgentMode: false"),
                patchSetComment("noise-2", "```\nDYNAMIC CONFIGURATION SETTINGS\nfoo: bar\n```"),
                patchSetComment("noise-3", "ReviewAI Message: Dynamic configuration modified"),
                patchSetComment(
                    "noise-4",
                    "Uploaded patch set 2.\n\nOutdated Votes:\n* Code-Review-1"),
                patchSetComment("real-1", "Please check null handling.")));

    AiHistory aiHistory =
        new AiHistory(
            config(),
            new ChangeSetData(AI_ACCOUNT_ID),
            new GerritClientData(
                null,
                List.of(),
                new CommentData(List.of(), new HashMap<>(), patchSetCommentMap),
                0),
            localizer());

    GerritComment patchSetMarker = new GerritComment();
    patchSetMarker.setFilename(GERRIT_PATCH_SET_FILENAME);

    assertEquals(
        List.of("user:Please check null handling."),
        historySummary(aiHistory.retrieveHistory(patchSetMarker)));
  }

  @Test
  public void openAiRequestPromptRetrievesFilteredHistoryOnlyOnce() throws Exception {
    AiHistoryFixture fixture =
        readFixture("nonAiDiscussionHistoryExcludesAiConversationMessages.json");
    HashMap<String, GerritComment> commentMap = mapById(fixture.inlineComments);
    GerritComment currentComment = commentMap.get(fixture.currentCommentId);
    assertNotNull(currentComment);
    Configuration config = config();
    when(config.getAiProviderType()).thenReturn(AiProviderType.OPENAI);
    IGerritClientPatchSet patchSet = mock(IGerritClientPatchSet.class);
    when(patchSet.getFileDiffsProcessed()).thenReturn(new HashMap<>());
    AiDataPromptRequests dataPrompt =
        new AiDataPromptRequests(
            config,
            new ChangeSetData(AI_ACCOUNT_ID),
            new GerritClientData(
                patchSet,
                List.of(),
                new CommentData(List.of(currentComment), commentMap, new HashMap<>()),
                0),
            localizer());

    dataPrompt.addMessageItem(0);

    assertEquals("final request", dataPrompt.getMessageItems().get(0).getRequest());
    assertEquals(
        List.of("user:This method needs tests."),
        historySummary(dataPrompt.getMessageItems().get(0).getHistory()));
  }

  private static List<String> historySummary(List<AiRequestMessage> history) {
    return history.stream()
        .map(message -> message.getRole() + ":" + message.getContent().trim())
        .collect(toList());
  }

  private static Configuration config() {
    Configuration config = mock(Configuration.class);
    when(config.getGerritUserName()).thenReturn("gpt");
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

  private static AiHistoryFixture readFixture(String fixtureName) throws IOException {
    return getGson()
        .fromJson(
            Files.readString(TEST_RESOURCES_PATH.resolve(FIXTURE_PATH + fixtureName)),
            AiHistoryFixture.class);
  }

  private static HashMap<String, GerritComment> mapById(List<GerritComment> comments) {
    HashMap<String, GerritComment> commentMap = new HashMap<>();
    if (comments == null) {
      return commentMap;
    }
    for (GerritComment comment : comments) {
      commentMap.put(comment.getId(), comment);
    }
    return commentMap;
  }

  private static GerritComment patchSetComment(String id, String message) {
    GerritComment comment = new GerritComment();
    comment.setId(id);
    comment.setFilename(GERRIT_PATCH_SET_FILENAME);
    comment.setPatchSet(1);
    comment.setDate("2026-06-23 10:00:00.000000000");
    comment.setUpdated("2026-06-23 10:00:00.000000000");
    comment.setMessage(message);
    GerritComment.Author author = new GerritComment.Author();
    author.setAccountId(1001);
    author.setName("Alice");
    comment.setAuthor(author);
    return comment;
  }

  @Test
  public void collectPreviouslyAddressedConcernsReturnsEmptyWhenNoAiComments() {
    HashMap<String, GerritComment> commentMap = mapById(
        List.of(
            userComment("c-1", null, "this is a review comment", "2026-04-09 10:00:00.000000"),
            aiComment("c-2", "c-1", "acknowledged", "2026-04-09 10:01:00.000000")));

    AiHistory aiHistory = createAiHistory(commentMap);

    List<AiHistory.AddressedConcern> concerns = aiHistory.collectPreviouslyAddressedConcerns();
    assertTrue(concerns.isEmpty());
  }

  @Test
  public void collectPreviouslyAddressedConcernsDetectsAddressedThreads() {
    // AI root comment → user reply → addressed (even without AI acknowledgment)
    HashMap<String, GerritComment> commentMap = mapById(
        List.of(
            aiComment("c-1", null, "This method needs tests.", "2026-04-09 10:00:00.000000"),
            userComment("c-2", "c-1", "/message UI tests are not in scope", "2026-04-09 10:01:00.000000")));

    AiHistory aiHistory = createAiHistory(commentMap);

    List<AiHistory.AddressedConcern> concerns = aiHistory.collectPreviouslyAddressedConcerns();
    assertEquals(1, concerns.size());
    assertEquals("This method needs tests.", concerns.get(0).getAiConcern());
    assertEquals("UI tests are not in scope", concerns.get(0).getUserResponse());
    assertTrue(concerns.get(0).getAiAcknowledgment().isEmpty());
  }

  @Test
  public void collectPreviouslyAddressedConcernsCapturesAiAcknowledgment() {
    // AI root → user reply → AI acknowledges
    HashMap<String, GerritComment> commentMap = mapById(
        List.of(
            aiComment("c-1", null, "No tests for combinedClickable.", "2026-04-09 10:00:00.000000"),
            userComment("c-2", "c-1", "/message UI tests not supported", "2026-04-09 10:01:00.000000"),
            aiComment("c-3", "c-2", "Dropped concern, approving.", "2026-04-09 10:02:00.000000")));

    AiHistory aiHistory = createAiHistory(commentMap);

    List<AiHistory.AddressedConcern> concerns = aiHistory.collectPreviouslyAddressedConcerns();
    assertEquals(1, concerns.size());
    AiHistory.AddressedConcern c = concerns.get(0);
    assertEquals("No tests for combinedClickable.", c.getAiConcern());
    assertEquals("UI tests not supported", c.getUserResponse());
    // AI acknowledgment exists and is non-empty
    assertEquals("Dropped concern, approving.", c.getAiAcknowledgment());
  }

  @Test
  public void collectPreviouslyAddressedConcernsSkipsThreadsWithoutUserReply() {
    // AI comment with no user reply → not collected
    HashMap<String, GerritComment> commentMap = mapById(
        List.of(
            aiComment("c-1", null, "Solo AI comment.", "2026-04-09 10:00:00.000000")));

    AiHistory aiHistory = createAiHistory(commentMap);

    List<AiHistory.AddressedConcern> concerns = aiHistory.collectPreviouslyAddressedConcerns();
    assertTrue(concerns.isEmpty());
  }

  @Test
  public void collectPreviouslyAddressedConcernsReturnsMostRecentFirst() {
    // Multiple threads with different timestamps → most recent first
    HashMap<String, GerritComment> commentMap = mapById(
        List.of(
            aiComment("c-1", null, "Old concern.", "2026-04-09 09:00:00.000000"),
            userComment("c-2", "c-1", "Fixed.", "2026-04-09 09:01:00.000000"),
            aiComment("c-3", null, "Newer concern.", "2026-04-09 10:00:00.000000"),
            userComment("c-4", "c-3", "Also fixed.", "2026-04-09 10:01:00.000000"),
            aiComment("c-5", null, "Latest concern.", "2026-04-09 11:00:00.000000"),
            userComment("c-6", "c-5", "Resolved.", "2026-04-09 11:01:00.000000")));

    AiHistory aiHistory = createAiHistory(commentMap);

    List<AiHistory.AddressedConcern> concerns = aiHistory.collectPreviouslyAddressedConcerns();
    assertEquals(3, concerns.size());
    assertEquals("Latest concern.", concerns.get(0).getAiConcern());
    assertEquals("Newer concern.", concerns.get(1).getAiConcern());
    assertEquals("Old concern.", concerns.get(2).getAiConcern());
  }

  @Test
  public void collectPreviouslyAddressedConcernsSkipsAutogeneratedAiComments() {
    GerritComment autoComment = aiComment("c-1", null, "Auto-generated.", "2026-04-09 10:00:00.000000");
    autoComment.setTag("autogenerated:gerrit:autoGenerated"); // mark as autogenerated
    HashMap<String, GerritComment> commentMap = mapById(
        List.of(
            autoComment,
            userComment("c-2", "c-1", "Fixed.", "2026-04-09 10:01:00.000000")));

    AiHistory aiHistory = createAiHistory(commentMap);

    List<AiHistory.AddressedConcern> concerns = aiHistory.collectPreviouslyAddressedConcerns();
    assertTrue(concerns.isEmpty());
  }

  @Test
  public void collectPreviouslyAddressedConcernsIncludesFilenameAndLine() {
    HashMap<String, GerritComment> commentMap = new HashMap<>();
    GerritComment aiRoot = aiComment("c-1", null, "Null check missing.", "2026-04-09 10:00:00.000000");
    aiRoot.setFilename("src/Foo.java");
    aiRoot.setLine(42);
    commentMap.put(aiRoot.getId(), aiRoot);
    GerritComment userReply = userComment("c-2", "c-1", "Added null check.", "2026-04-09 10:01:00.000000");
    commentMap.put(userReply.getId(), userReply);

    AiHistory aiHistory = createAiHistory(commentMap);

    List<AiHistory.AddressedConcern> concerns = aiHistory.collectPreviouslyAddressedConcerns();
    assertEquals(1, concerns.size());
    assertEquals("src/Foo.java", concerns.get(0).getFilename());
    assertEquals(Integer.valueOf(42), concerns.get(0).getLine());
  }

  private AiHistory createAiHistory(HashMap<String, GerritComment> commentMap) {
    return new AiHistory(
        config(),
        new ChangeSetData(AI_ACCOUNT_ID),
        new GerritClientData(
            null,
            List.of(),
            new CommentData(List.of(), commentMap, new HashMap<>()),
            0),
        localizer());
  }

  private GerritComment aiComment(String id, String inReplyTo, String message, String updated) {
    GerritComment comment = new GerritComment();
    comment.setId(id);
    comment.setInReplyTo(inReplyTo);
    comment.setMessage(message);
    comment.setUpdated(updated);
    comment.setPatchSet(1);
    GerritComment.Author author = new GerritComment.Author();
    author.setAccountId(AI_ACCOUNT_ID);
    comment.setAuthor(author);
    return comment;
  }

  private GerritComment userComment(String id, String inReplyTo, String message, String updated) {
    GerritComment comment = new GerritComment();
    comment.setId(id);
    comment.setInReplyTo(inReplyTo);
    comment.setMessage(message);
    comment.setUpdated(updated);
    comment.setPatchSet(1);
    GerritComment.Author author = new GerritComment.Author();
    author.setAccountId(1001);
    comment.setAuthor(author);
    return comment;
  }

  private static class AiHistoryFixture {
    private List<GerritComment> patchSetComments;
    private List<GerritComment> inlineComments;
    private String currentCommentId;
  }
}
