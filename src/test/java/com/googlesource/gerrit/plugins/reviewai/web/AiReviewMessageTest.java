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

package com.googlesource.gerrit.plugins.reviewai.web;

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.FileApi;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.common.DiffInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerBaseProvider;
import com.google.gerrit.json.OutputFormat;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManager.KEY_DYNAMIC_CONFIG;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewMessageTest extends TestBase {
  @Mock private ChangeResource changeResource;
  @Mock private ConfigCreator configCreator;
  @Mock private Configuration config;
  @Mock private GerritApi gerritApi;
  @Mock private AiReviewPermission aiReviewPermission;
  @Mock private PluginDataHandlerBaseProvider pluginDataHandlerBaseProvider;
  @Mock private PluginDataHandler pluginDataHandler;
  @Mock private GitRepositoryManager repositoryManager;
  @Mock private Changes changes;
  @Mock private ChangeApi changeApi;
  @Mock private ChangeApi.CommentsRequest commentsRequest;
  @Mock private RevisionApi revisionApi;
  @Mock private FileApi fileApi;

  private AiReviewMessage view;
  private Path realChangeDataPath;

  @Before
  public void setUp() throws Exception {
    Change change =
        new Change(CHANGE_ID, Change.id(1), Account.id(100), BRANCH_NAME, Instant.now());
    when(changeResource.getChange()).thenReturn(change);
    when(changeResource.getProject()).thenReturn(PROJECT_NAME);
    when(configCreator.createConfig(PROJECT_NAME, CHANGE_ID)).thenReturn(config);
    when(config.getGerritUserName()).thenReturn("gpt");
    when(config.getGerritApi()).thenReturn(gerritApi);
    when(config.getLocaleDefault()).thenReturn(Locale.ENGLISH);
    when(config.getAiReviewCommitMessages()).thenReturn(true);
    when(config.getEnabledFileExtensions()).thenReturn(List.of("py"));
    when(config.getAiModels())
        .thenReturn(List.of("OpenAI/gpt-4.1", "MoonShot/moonshot-v1-8k"));
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id(PROJECT_NAME.get(), change.getChangeId())).thenReturn(changeApi);
    when(changes.id(PROJECT_NAME.get(), BRANCH_NAME.shortName(), CHANGE_ID.get()))
        .thenReturn(changeApi);
    when(changeApi.current()).thenReturn(revisionApi);
    when(changeApi.commentsRequest()).thenReturn(commentsRequest);
    when(commentsRequest.get()).thenReturn(Map.of());
    when(revisionApi.review(any())).thenReturn(null);
    when(pluginDataHandlerBaseProvider.get(CHANGE_ID.toString())).thenReturn(pluginDataHandler);
    realChangeDataPath = tempFolder.getRoot().toPath().resolve(CHANGE_ID + ".data");
    when(mockPluginDataPath.resolve(CHANGE_ID + ".data")).thenReturn(realChangeDataPath);
    view =
        new AiReviewMessage(
            configCreator,
            gerritApi,
            aiReviewPermission,
            pluginDataHandlerBaseProvider,
            repositoryManager,
            mockPluginDataPath,
            null,
            getTestReviewAiDb());
  }

  @Test(expected = AuthException.class)
  public void rejectsMessageWhenCanAiReviewIsFalse() throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review";
    doThrow(new AuthException("AI review is not allowed for this change"))
        .when(aiReviewPermission)
        .checkCanAiReview(changeResource);

    view.apply(changeResource, input);
  }

  @Test
  @SuppressWarnings("unchecked")
  public void storesSelectedModelNameFromReviewAiDropdown() throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review";
    input.modelName = "MoonShot/moonshot-v1-8k";

    view.apply(changeResource, input);

    ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
    verify(pluginDataHandler).setJsonValue(eq(KEY_DYNAMIC_CONFIG), captor.capture());
    assertEquals("MoonShot/moonshot-v1-8k", captor.getValue().get("selectedAiModel"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void storesNonDefaultSelectedModelIdAlias() throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review";
    input.modelId = "MoonShot/moonshot-v1-8k";

    view.apply(changeResource, input);

    ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
    verify(pluginDataHandler).setJsonValue(eq(KEY_DYNAMIC_CONFIG), captor.capture());
    assertEquals("MoonShot/moonshot-v1-8k", captor.getValue().get("selectedAiModel"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void removesDefaultSelectedModelIdAliasWhenNoOtherDynamicConfigExists() throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review";
    input.modelId = "OpenAI/gpt-4.1";

    view.apply(changeResource, input);

    verify(pluginDataHandler).removeValue(KEY_DYNAMIC_CONFIG);
    verify(pluginDataHandler, never()).setJsonValue(eq(KEY_DYNAMIC_CONFIG), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void storesDefaultSelectedModelWhenOtherDynamicConfigExists() throws Exception {
    when(pluginDataHandler.getJsonObjectValue(KEY_DYNAMIC_CONFIG, String.class))
        .thenReturn(Map.of("aiReviewTemperature", "0.1"));
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review";
    input.modelId = "OpenAI/gpt-4.1";

    view.apply(changeResource, input);

    ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
    verify(pluginDataHandler).setJsonValue(eq(KEY_DYNAMIC_CONFIG), captor.capture());
    assertEquals("OpenAI/gpt-4.1", captor.getValue().get("selectedAiModel"));
    assertEquals("0.1", captor.getValue().get("aiReviewTemperature"));
  }

  @Test
  public void reviewAgentHelpCommandReturnsDirectResponseWithoutPostingGerritMessage()
      throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/help";
    input.reviewAgent = true;

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals(true, output.ok);
    assertFalse(output.waitForAssistantReply);
    assertTrue(output.responseText.contains("AVAILABLE COMMANDS"));
    verify(revisionApi, never()).review(any());
  }

  @Test
  public void reviewAgentShowCommandReturnsDirectResponseWithoutPostingGerritMessage()
      throws Exception {
    new PluginDataHandler(realChangeDataPath, getTestReviewAiDb())
        .setJsonValue(KEY_DYNAMIC_CONFIG, Map.of("aiModel", "OpenAI/gpt-4.1"));
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/show --config";
    input.reviewAgent = true;

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals(true, output.ok);
    assertFalse(output.waitForAssistantReply);
    assertTrue(output.responseText.contains("DYNAMIC CONFIGURATION SETTINGS"));
    assertTrue(output.responseText.contains("OpenAI/gpt-4.1"));
    assertTrue(
        output.responseText.contains(
            "Unable to execute command: Message Debugging functionalities are disabled"));
    verify(revisionApi, never()).review(any());
  }

  @Test
  public void reviewAgentShowPromptsReturnsRealPrompt() throws Exception {
    when(config.getEnableMessageDebugging()).thenReturn(true);
    String patch = readTestFile("__files/openai/gerritFormattedPatch.txt");
    when(revisionApi.patch()).thenReturn(BinaryResult.create(patch));
    when(revisionApi.file("test_file_1.py")).thenReturn(fileApi);
    DiffInfo diffInfo = readTestFileToClass("__files/openai/gerritPatchSetDiffTestFile.json");
    when(fileApi.diff(0)).thenReturn(diffInfo);
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/show --prompts";
    input.reviewAgent = true;

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals(true, output.ok);
    List<String> expectedTitles =
        List.of(readTestFile("__files/commands/showPromptsTitles.txt").split("\\R"));
    for (String expectedTitle : expectedTitles) {
      assertTrue(output.responseText.contains(expectedTitle));
    }
    List<String> removedTitles =
        List.of(readTestFile("__files/commands/showPromptsRemovedTitle.txt").split("\\R"));
    for (String removedTitle : removedTitles) {
      assertFalse(output.responseText.contains(removedTitle));
    }
    assertTrue(output.responseText.contains("Subject: Minor fixes"));
    assertTrue(output.responseText.contains("diff --git a/test_file_1.py b/test_file_1.py"));
    verify(revisionApi).patch();
    verify(revisionApi, never()).review(any());
  }

  @Test
  public void reviewAgentReviewCommandReturnsDynamicConfigPreambleAndPostsGerritMessage()
      throws Exception {
    new PluginDataHandler(realChangeDataPath, getTestReviewAiDb())
        .setJsonValue(KEY_DYNAMIC_CONFIG, Map.of("aiModel", "OpenAI/gpt-4.1"));
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/forget_thread /review";
    input.reviewAgent = true;

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals(true, output.ok);
    assertTrue(output.waitForAssistantReply);
    assertTrue(output.responseText.contains("DYNAMIC CONFIGURATION SETTINGS"));
    assertTrue(output.responseText.contains("OpenAI/gpt-4.1"));
    verify(revisionApi).review(any());
  }

  @Test
  public void reviewAgentReviewCommandHidesDefaultSelectedModelOnlyPreamble() throws Exception {
    new PluginDataHandler(realChangeDataPath, getTestReviewAiDb())
        .setJsonValue(KEY_DYNAMIC_CONFIG, Map.of("selectedAiModel", "OpenAI/gpt-4.1"));
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review";
    input.reviewAgent = true;

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals(true, output.ok);
    assertTrue(output.waitForAssistantReply);
    assertEquals(null, output.responseText);
    verify(revisionApi).review(any());
  }

  @Test
  public void reviewAgentReviewCommandShowsNonDefaultSelectedModelOnlyPreamble() throws Exception {
    new PluginDataHandler(realChangeDataPath, getTestReviewAiDb())
        .setJsonValue(
            KEY_DYNAMIC_CONFIG,
            Map.of("selectedAiModel", "MoonShot/moonshot-v1-8k"));
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review";
    input.reviewAgent = true;

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals(true, output.ok);
    assertTrue(output.waitForAssistantReply);
    assertTrue(output.responseText.contains("DYNAMIC CONFIGURATION SETTINGS"));
    assertTrue(output.responseText.contains("MoonShot/moonshot-v1-8k"));
    verify(revisionApi).review(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  public void reviewAgentReviewCommandUsesGerritMessageIdForRequestStatus() throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review";
    input.reviewAgent = true;
    input.requestId = "request-1";
    CommentInfo postedComment = new CommentInfo();
    postedComment.id = "comment-1";
    postedComment.message = "@gpt /review";
    postedComment.changeMessageId = "message-1";
    postedComment.setUpdated(Instant.parse("2026-04-30T10:00:00Z"));
    when(commentsRequest.get()).thenReturn(Map.of("/PATCHSET_LEVEL", List.of(postedComment)));

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals("message-1", output.requestId);
    ArgumentCaptor<ReviewInput> reviewCaptor = ArgumentCaptor.forClass(ReviewInput.class);
    verify(revisionApi).review(reviewCaptor.capture());
    assertTrue(
        reviewCaptor.getValue().comments.values().stream()
            .flatMap(List::stream)
            .anyMatch(comment -> "@gpt /review".equals(comment.message)));
    ArgumentCaptor<Map<String, Object>> statusCaptor = ArgumentCaptor.forClass(Map.class);
    verify(pluginDataHandler, atLeastOnce())
        .setJsonValue(eq("reviewAgentRequestStatuses"), statusCaptor.capture());
    assertNotNull(statusCaptor.getValue().get("message-1"));
  }

  @Test
  public void reviewAgentPatchsetScopeReviewReturnsPartialReviewPreambleAndPostsGerritMessage()
      throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review --scope=patchset";
    input.reviewAgent = true;

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals(true, output.ok);
    assertTrue(output.waitForAssistantReply);
    assertEquals(
        readTestFile("__files/commands/partialReviewPositiveScoreNoVoteSystemMessage.txt")
            .stripTrailing(),
        output.responseText);
    verify(revisionApi).review(any());
  }

  @Test
  public void reviewAgentInvalidReviewScopeReturnsDirectSystemMessageWithoutPostingGerritMessage()
      throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review --scope=wrong";
    input.reviewAgent = true;

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals(true, output.ok);
    assertFalse(output.waitForAssistantReply);
    assertEquals(
        "ReviewAI Message: Invalid value for option `SCOPE`: `wrong`. Supported values are: [patchset, " +
            "commit_message]",
        output.responseText);
    verify(revisionApi, never()).review(any());
  }

  @Test
  public void reviewAgentConfigureCommandBlockedByDebuggingReturnsDirectSystemMessage()
      throws Exception {
    new PluginDataHandler(realChangeDataPath, getTestReviewAiDb())
        .setJsonValue(KEY_DYNAMIC_CONFIG, Map.of("aiModel", "OpenAI/gpt-4.1"));
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/configure";
    input.reviewAgent = true;

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals(true, output.ok);
    assertFalse(output.waitForAssistantReply);
    assertEquals(
        readTestFile("__files/commands/debuggingMessagesDisabledSystemMessage.txt")
            .stripTrailing(),
        output.responseText);
    verify(revisionApi, never()).review(any());
  }

  @Test
  public void reviewAgentConfigureCommandWithDebuggingWaitsForPostedCommandResponse()
      throws Exception {
    when(config.getEnableMessageDebugging()).thenReturn(true);
    new PluginDataHandler(realChangeDataPath, getTestReviewAiDb())
        .setJsonValue(KEY_DYNAMIC_CONFIG, Map.of("aiModel", "OpenAI/gpt-4.1"));
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/configure";
    input.reviewAgent = true;

    AiReviewMessage.Output output = view.apply(changeResource, input).value();

    assertEquals(true, output.ok);
    assertTrue(output.waitForAssistantReply);
    assertEquals(null, output.responseText);
    verify(revisionApi).review(any());
  }

  @Test
  public void helpCommandFromNonReviewAgentPathPostsGerritMessage() throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/help";

    view.apply(changeResource, input);

    verify(revisionApi).review(any());
  }

  @Test
  public void reviewAgentMessageCommandContainingHelpPostsGerritMessage() throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/message explain /help";
    input.reviewAgent = true;

    view.apply(changeResource, input);

    verify(revisionApi).review(any());
  }

  @Test(expected = BadRequestException.class)
  public void rejectsInvalidSelectedModel() throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review";
    input.modelName = "OpenAI/not-configured";

    view.apply(changeResource, input);
  }

  private String readTestFile(String filename) throws Exception {
    return Files.readString(Paths.get("src/test/resources").resolve(filename));
  }

  private DiffInfo readTestFileToClass(String filename) throws Exception {
    return OutputFormat.JSON.newGson().fromJson(readTestFile(filename), DiffInfo.class);
  }
}
