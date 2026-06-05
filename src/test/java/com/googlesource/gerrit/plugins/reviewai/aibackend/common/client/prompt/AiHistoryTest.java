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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

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

public class AiHistoryTest {
  private static final int AI_ACCOUNT_ID = 7;
  private static final Path TEST_RESOURCES_PATH = Paths.get("src/test/resources");
  private static final String FIXTURE_PATH = "__files/aibackend/common/client/prompt/";

  @Test
  public void patchSetHistoryStartsAfterLatestForgetThreadCommand() throws Exception {
    AiHistoryFixture fixture =
        readFixture("patchSetHistoryStartsAfterLatestForgetThreadCommand.json");
    HashMap<String, GerritComment> patchSetCommentMap = mapById(fixture.patchSetComments);

    AiHistory aiHistory =
        new AiHistory(
            config(),
            new ChangeSetData(AI_ACCOUNT_ID, -2, 2),
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
            new ChangeSetData(AI_ACCOUNT_ID, -2, 2),
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
            new ChangeSetData(AI_ACCOUNT_ID, -2, 2),
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
            new ChangeSetData(AI_ACCOUNT_ID, -2, 2),
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

  private static class AiHistoryFixture {
    private List<GerritComment> patchSetComments;
    private List<GerritComment> inlineComments;
    private String currentCommentId;
  }
}
