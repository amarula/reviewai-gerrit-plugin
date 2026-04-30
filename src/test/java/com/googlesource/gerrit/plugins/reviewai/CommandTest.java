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

package com.googlesource.gerrit.plugins.reviewai;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.json.OutputFormat;
import com.google.gson.Gson;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAgentRequestStatusStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.listener.EventHandlerTask;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.OpenAiReviewTestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.OpenAiUriResourceLocator;
import com.googlesource.gerrit.plugins.reviewai.utils.TextUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.googlesource.gerrit.plugins.reviewai.config.Configuration.KEY_DIRECTIVES;
import static com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManager.KEY_DYNAMIC_CONFIG;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.TemplateUtils.renderTemplate;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.sortTextLines;
import static org.mockito.Mockito.when;

public class CommandTest extends OpenAiReviewTestBase {
  private static final String UNSUPPORTED_ONLY_PATCH_FILE = "__files/commands/unsupportedOnlyPatch.txt";

  @Before
  public void setUp() {
    setupPluginData();

    // Mock the PluginData annotation global behavior
    when(mockPluginDataPath.resolve("global.data")).thenReturn(realPluginDataPath);
  }

  protected void initTest() {
    super.initTest();

    openAiPrompt.setCommentEvent(true);
  }

  @Override
  protected void setupMockRequests() throws RestApiException {
    super.setupMockRequests();

    setupMockRequestCreateResponse("openAiResponseRequest.json");
  }

  private void setupCommandComment(String command) throws RestApiException {
    String commentJson =
        renderTemplate(
            readTestFile("__files/commands/commandCommentTemplate.json"),
            Map.of("command", command));
    Map<String, List<CommentInfo>> comments = readContentToType(commentJson, COMMENTS_GERRIT_TYPE);
    mockGerritChangeCommentsApiCall(comments);
  }

  private void enableMessageDebugging() {
    when(config.getEnableMessageDebugging()).thenReturn(true);
  }

  private PluginDataHandler getChangeDataHandler() {
    Path realChangeDataPath = tempFolder.getRoot().toPath().resolve(TestBase.CHANGE_ID + ".data");
    when(mockPluginDataPath.resolve(TestBase.CHANGE_ID + ".data")).thenReturn(realChangeDataPath);
    PluginDataHandlerProvider provider =
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange());
    PluginDataHandler changeHandler = provider.getChangeScope();
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeHandler);

    return changeHandler;
  }

  @Test
  public void commandMessage() throws RestApiException {
    String message = "is it OK to use \"and/or\"?";
    setupCommandComment("/message " + message);
    setupMockRequestCreateResponse("openAiResponseRequest.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    testRequestSent();
    String userPrompt = getUserPrompt();
    Assert.assertTrue(userPrompt.contains(message));
  }

  @Test
  public void commandReview() throws RestApiException {
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);

    setupCommandComment("/review");
    String reviewMessage = readTestFile("__files/commands/review.json");
    setupMockRequestCreateResponse("openAiResponseRequest.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();

    Gson gson = OutputFormat.JSON_COMPACT.newGson();
    Assert.assertEquals(reviewMessage, gson.toJson(captor.getAllValues().get(0)));
  }

  @Test
  public void commandReviewShowsDynamicConfigAfterChainedForgetThreadCommand() throws Exception {
    PluginDataHandler changeHandler = getChangeDataHandler();
    changeHandler.setJsonValue(KEY_DYNAMIC_CONFIG, Map.of("aiModel", "OpenAI/gpt-4.1"));
    setupCommandComment("/forget_thread /review");
    setupMockRequestCreateResponse("openAiResponseRequest.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    Assert.assertTrue(captor.getValue().message.contains("DYNAMIC CONFIGURATION SETTINGS"));
    Assert.assertTrue(captor.getValue().message.contains("OpenAI/gpt-4.1"));
  }

  @Test
  public void commandReviewAllowedWhenAiReviewAccessIsNotConfigured() throws RestApiException {
    when(aiReviewPermission.isAiReviewExplicitlyDisallowed(
            PROJECT_NAME, BRANCH_NAME.branch(), eventUser))
        .thenReturn(false);

    setupCommandComment("/review");
    setupMockRequestCreateResponse("openAiResponseRequest.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    testRequestSent();
  }

  @Test
  public void commandReviewSkippedWhenAiReviewAccessIsExplicitlyDisallowed()
      throws RestApiException {
    when(aiReviewPermission.isAiReviewExplicitlyDisallowed(
            PROJECT_NAME, BRANCH_NAME.branch(), eventUser))
        .thenReturn(true);

    setupCommandComment("/review");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Mockito.verify(revisionApiMock, Mockito.never()).review(Mockito.any());
  }

  @Test
  public void commandReviewSkippedWhenAiReviewAccessIsExplicitlyDisallowedAndEventHasOnlyUsername()
      throws RestApiException {
    includeEventAccountId = false;
    when(aiReviewPermission.isAiReviewExplicitlyDisallowed(
            PROJECT_NAME, BRANCH_NAME.branch(), eventUser))
        .thenReturn(true);

    setupCommandComment("/review");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Mockito.verify(aiReviewPermission)
        .isAiReviewExplicitlyDisallowed(PROJECT_NAME, BRANCH_NAME.branch(), eventUser);
    Mockito.verify(revisionApiMock, Mockito.never()).review(Mockito.any());
  }

  @Test
  public void commandHelp() throws Exception {
    setupCommandComment("/help");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    String systemMessage = changeSetData.getReviewSystemMessage();
    Assert.assertTrue(systemMessage.contains("AVAILABLE COMMANDS"));
    Assert.assertTrue(systemMessage.contains("`/help <command>`"));
    Assert.assertTrue(systemMessage.contains("`/help`"));
    Assert.assertTrue(systemMessage.contains("`/message <text>`"));
    Assert.assertTrue(
        systemMessage.contains(
            "`/review [--scope=patchset|commit_message] [--filter=true|false] [--debug]`"));
    Assert.assertTrue(
        systemMessage.contains(
            "`/configure`, `/directives`, and `/show`, plus the `--debug` option on review commands, require `enableMessageDebugging=true`"));
  }

  @Test
  public void commandHelpDoesNotRetrievePatchSet() throws Exception {
    setupCommandComment("/help");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Mockito.verify(revisionApiMock, Mockito.never()).patch();
    Mockito.verify(revisionApiMock, Mockito.never()).file(Mockito.anyString());
  }

  @Test
  public void commandHelpSpecificCommand() throws Exception {
    setupCommandComment("/help /review");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    String systemMessage = changeSetData.getReviewSystemMessage();
    Assert.assertEquals(false, changeSetData.getForcedReview());
    Assert.assertTrue(systemMessage.contains("HELP FOR `/review`"));
    Assert.assertTrue(
        systemMessage.contains(
            "`/review [--scope=patchset|commit_message] [--filter=true|false] [--debug]`"));
    Assert.assertTrue(systemMessage.contains("Triggers a review of the full Change Set"));
  }

  @Test
  public void commandReviewCommitMessageScopeIgnoresCommitMessageReviewConfig() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("aiReviewCommitMessages"), Mockito.anyBoolean()))
        .thenReturn(false);

    setupCommandComment(reviewCommandWithScope(ReviewScope.COMMIT_MESSAGE));
    setupMockRequestCreateResponse("openAiRunStepsResponse.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    testRequestSent();
    Assert.assertTrue(getInputContent().contains("Minor fixes"));
    Assert.assertTrue(getInputContent().contains("diff --git"));
    Assert.assertTrue(
        aiRequestBody
            .get("instructions")
            .getAsString()
            .contains("Git commit message expert"));
  }

  @Test
  public void commandReviewPatchsetScopeExcludesCommitMessage() throws Exception {
    setupCommandComment(reviewCommandWithScope(ReviewScope.PATCHSET));
    setupMockRequestCreateResponse("openAiRunStepsResponse.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    testRequestSent();
    Assert.assertFalse(getInputContent().contains("Subject: Minor fixes"));
    Assert.assertTrue(getInputContent().contains("diff --git"));
    Assert.assertFalse(
        aiRequestBody
            .get("instructions")
            .getAsString()
            .contains("You MUST review the commit message"));
  }

  @Test
  public void commandReviewPatchsetScopeSkipsPositiveGlobalScore() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);
    setupCommandComment(reviewCommandWithScope(ReviewScope.PATCHSET) + " --filter=false");
    setupMockRequestCreateResponseFromBody(positiveReviewResponse(), null, null);

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    Assert.assertNull(captor.getValue().labels);
    Assert.assertEquals(
        readTestFile("__files/commands/partialReviewPositiveScoreNoVoteSystemMessage.txt")
            .stripTrailing(),
        captor.getValue().message);
    Assert.assertFalse(captor.getValue().comments.isEmpty());
  }

  @Test
  public void commandReviewCommitMessageScopeSkipsPositiveGlobalScore() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("enabledVoting"), Mockito.anyBoolean()))
        .thenReturn(true);
    setupCommandComment(reviewCommandWithScope(ReviewScope.COMMIT_MESSAGE) + " --filter=false");
    setupMockRequestCreateResponseFromBody(positiveReviewResponse(), null, null);

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    Assert.assertNull(captor.getValue().labels);
    Assert.assertEquals(
        readTestFile("__files/commands/partialReviewPositiveScoreNoVoteSystemMessage.txt")
            .stripTrailing(),
        captor.getValue().message);
    Assert.assertFalse(captor.getValue().comments.isEmpty());
  }

  @Test
  public void commandReviewShowsSystemMessageWhenNoFilesRemainAfterFiltering() throws Exception {
    when(globalConfig.getString(Mockito.eq("enabledFileExtensions"), Mockito.anyString()))
        .thenReturn(".py");
    when(revisionApiMock.patch())
        .thenReturn(BinaryResult.create(readTestFile(UNSUPPORTED_ONLY_PATCH_FILE)));
    setupCommandComment("/review");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = ArgumentCaptor.forClass(ReviewInput.class);
    Mockito.verify(revisionApiMock).review(captor.capture());
    Assert.assertEquals(
        "SYSTEM MESSAGE: No update to show for this Change Set", captor.getValue().message);
    Assert.assertNull(captor.getValue().comments);
    WireMock.verify(
        0, WireMock.postRequestedFor(WireMock.urlEqualTo(OpenAiUriResourceLocator.responsesUri())));
  }

  @Test
  public void commandReviewCompletesPendingReviewAgentStatusWhenNoCommentsRequireAction()
      throws Exception {
    ReviewAgentRequestStatusStore statusStore =
        new ReviewAgentRequestStatusStore(getChangeDataHandler());
    statusStore.pending("request-1", "/review");
    mockGerritChangeCommentsApiCall(Map.of());

    EventHandlerTask.Result result =
        handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ReviewAgentRequestStatusStore.RequestStatus status = statusStore.get("request-1");
    Assert.assertEquals(EventHandlerTask.Result.NOT_SUPPORTED, result);
    Assert.assertEquals(ReviewAgentRequestStatusStore.STATUS_COMPLETED, status.status);
    Assert.assertEquals(
        "SYSTEM MESSAGE: No update to show for this Change Set",
        status.responseText);
  }

  @Test
  public void commandReviewScopeRejectsUnsupportedValue() throws Exception {
    setupCommandComment("/review --scope=full");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertEquals(
        String.format(
            localizer.getText("message.command.option.value.invalid"),
            "SCOPE",
            "full",
            ReviewScope.reviewCommandOptionValues()),
        changeSetData.getReviewSystemMessage());
  }

  private String reviewCommandWithScope(ReviewScope reviewScope) {
    return "/review --scope=" + reviewScope.getCommandOptionValue();
  }

  private String positiveReviewResponse() {
    return readTestFile(RESOURCE_OPENAI_PATH + "openAiRunStepsResponse.json")
        .replace("\\\"score\\\": -1.0", "\\\"score\\\": 1.0");
  }

  @Test
  public void commandConfigure() throws Exception {
    String dynamicKey = "aiModels";
    String dynamicValue = getGson().toJson(List.of("OpenAI/DUMMY_MODEL"));
    setupCommandComment(String.format("/configure --%s=%s", dynamicKey, dynamicValue));
    enableMessageDebugging();
    PluginDataHandler changeHandler = getChangeDataHandler();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    String dynamicChanges = changeHandler.getValue(KEY_DYNAMIC_CONFIG);
    String expectedChanges = getGson().toJson(Map.of(dynamicKey, dynamicValue));
    Assert.assertEquals(expectedChanges, dynamicChanges);
  }

  @Test
  public void commandAddDirective() throws Exception {
    List<String> directives = List.of("DUMMY DIRECTIVE");
    setupCommandComment(String.format("/directives %s", directives.get(0)));
    enableMessageDebugging();
    PluginDataHandler changeHandler = getChangeDataHandler();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    String dynamicChanges = changeHandler.getValue(KEY_DYNAMIC_CONFIG);
    String expectedChanges = getGson().toJson(Map.of(KEY_DIRECTIVES, getGson().toJson(directives)));
    Assert.assertEquals(expectedChanges, dynamicChanges);
  }

  @Test
  public void commandDumpStoredData() throws Exception {
    setupCommandComment("/show --local_data");
    enableMessageDebugging();

    PluginDataHandlerProvider provider =
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange());
    PluginDataHandler globalHandler = provider.getGlobalScope();
    when(pluginDataHandlerProvider.getGlobalScope()).thenReturn(globalHandler);
    PluginDataHandler projectHandler = provider.getProjectScope();
    when(pluginDataHandlerProvider.getProjectScope()).thenReturn(projectHandler);

    globalHandler.setValue("configKey1", "configValue1");
    globalHandler.setValue("configKey2", "{\"configSubKey\": \"configSubValue\"}");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    // The dump order may vary, so the contents are compared in sorted form.
    String systemMessage =
        sortTextLines(readTestFile("__files/commands/dumpStoredDataSystemMessage.txt").stripTrailing());
    Assert.assertEquals(
        systemMessage, sortTextLines(changeSetData.getReviewSystemMessage().stripTrailing()));
  }

  @Test
  public void commandDumpConfig() throws Exception {
    setupCommandComment("/show --config");
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    String systemMessage = readTestFile("__files/commands/dumpConfig.txt").stripTrailing();
    Assert.assertEquals(systemMessage, changeSetData.getReviewSystemMessage().stripTrailing());
  }

  @Test
  public void commandShowPromptsUsesSafeMarkdownFence() throws Exception {
    setupCommandComment("/show --prompts");
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = ArgumentCaptor.forClass(ReviewInput.class);
    Mockito.verify(revisionApiMock).review(captor.capture());

    String reviewMessage = captor.getValue().message;
    Assert.assertTrue(reviewMessage.contains("SYSTEM MESSAGE:"));
    List<String> expectedTitles =
        List.of(readTestFile("__files/commands/showPromptsTitles.txt").split("\\R"));
    for (String expectedTitle : expectedTitles) {
      Assert.assertTrue(reviewMessage.contains(expectedTitle));
    }
    List<String> removedTitles =
        List.of(readTestFile("__files/commands/showPromptsRemovedTitle.txt").split("\\R"));
    for (String removedTitle : removedTitles) {
      Assert.assertFalse(reviewMessage.contains(removedTitle));
    }
    Assert.assertTrue(
        reviewMessage.indexOf(expectedTitles.get(0))
            < reviewMessage.indexOf(expectedTitles.get(1)));
    Assert.assertTrue(
        reviewMessage.indexOf(expectedTitles.get(1))
            < reviewMessage.indexOf(expectedTitles.get(2)));
    Assert.assertTrue(reviewMessage.contains("Review the following Patch Set:  ` ` `"));
    Assert.assertTrue(reviewMessage.contains("Review the following Commit Message:  ` ` `"));
    Assert.assertTrue(reviewMessage.contains("Subject: Minor fixes"));
    Assert.assertTrue(reviewMessage.contains("diff --git a/test_file_1.py b/test_file_1.py"));
    String codeFence = TextUtils.CODE_DELIMITER;
    for (String expectedTitle : expectedTitles) {
      Assert.assertTrue(reviewMessage.contains(codeFence + "\n" + expectedTitle));
    }
    Assert.assertEquals(6, reviewMessage.split(codeFence, -1).length - 1);
  }

  @Test
  public void commandShowPromptsRetrievesPatchSet() throws Exception {
    setupCommandComment("/show --prompts");
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Mockito.verify(revisionApiMock).patch();
    Mockito.verify(revisionApiMock).file("test_file_1.py");
  }

  @Test
  public void commandShowPromptsFullScopeIncludesOnlyFullReviewPrompt() throws Exception {
    setupCommandComment("/show --prompts --scope=" + ReviewScope.FULL.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    assertOnlyScopedShowBlock(
        changeSetData.getReviewSystemMessage(),
        "__files/commands/showPromptsTitles.txt",
        ReviewScope.FULL);
  }

  @Test
  public void commandShowPromptsPatchSetScopeIncludesOnlyPatchSetPrompt() throws Exception {
    setupCommandComment("/show --prompts --scope=" + ReviewScope.PATCHSET.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    assertOnlyScopedShowBlock(
        changeSetData.getReviewSystemMessage(),
        "__files/commands/showPromptsTitles.txt",
        ReviewScope.PATCHSET);
  }

  @Test
  public void commandShowPromptsCommitMessageScopeIncludesOnlyCommitMessagePrompt()
      throws Exception {
    setupCommandComment(
        "/show --prompts --scope=" + ReviewScope.COMMIT_MESSAGE.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    assertOnlyScopedShowBlock(
        changeSetData.getReviewSystemMessage(),
        "__files/commands/showPromptsTitles.txt",
        ReviewScope.COMMIT_MESSAGE);
  }

  @Test
  public void commandShowInstructionsIncludesAllReviewScopes() throws Exception {
    setupCommandComment("/show --instructions");
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    String systemMessage = changeSetData.getReviewSystemMessage();
    List<String> expectedTitles =
        List.of(readTestFile("__files/commands/showInstructionsTitles.txt").split("\\R"));
    for (String expectedTitle : expectedTitles) {
      Assert.assertTrue(systemMessage.contains(expectedTitle));
    }
    List<String> removedTitles =
        List.of(readTestFile("__files/commands/showInstructionsRemovedTitle.txt").split("\\R"));
    for (String removedTitle : removedTitles) {
      Assert.assertFalse(systemMessage.contains(removedTitle));
    }
    Assert.assertTrue(
        systemMessage.indexOf(expectedTitles.get(0))
            < systemMessage.indexOf(expectedTitles.get(1)));
    Assert.assertTrue(
        systemMessage.indexOf(expectedTitles.get(1))
            < systemMessage.indexOf(expectedTitles.get(2)));
    String codeFence = TextUtils.CODE_DELIMITER;
    for (String expectedTitle : expectedTitles) {
      Assert.assertTrue(systemMessage.contains(codeFence + "\n" + expectedTitle));
    }
    Assert.assertEquals(6, systemMessage.split(codeFence, -1).length - 1);
  }

  @Test
  public void commandShowInstructionsFullScopeIncludesOnlyFullReviewInstructions()
      throws Exception {
    setupCommandComment(
        "/show --instructions --scope=" + ReviewScope.FULL.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    assertOnlyScopedShowBlock(
        changeSetData.getReviewSystemMessage(),
        "__files/commands/showInstructionsTitles.txt",
        ReviewScope.FULL);
  }

  @Test
  public void commandShowInstructionsPatchSetScopeIncludesOnlyPatchSetInstructions()
      throws Exception {
    setupCommandComment(
        "/show --instructions --scope=" + ReviewScope.PATCHSET.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    assertOnlyScopedShowBlock(
        changeSetData.getReviewSystemMessage(),
        "__files/commands/showInstructionsTitles.txt",
        ReviewScope.PATCHSET);
  }

  @Test
  public void commandShowInstructionsCommitMessageScopeIncludesOnlyCommitMessageInstructions()
      throws Exception {
    setupCommandComment(
        "/show --instructions --scope=" + ReviewScope.COMMIT_MESSAGE.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    assertOnlyScopedShowBlock(
        changeSetData.getReviewSystemMessage(),
        "__files/commands/showInstructionsTitles.txt",
        ReviewScope.COMMIT_MESSAGE);
  }

  @Test
  public void commandShowScopeWithoutPromptsOrInstructionsIsRejected() throws Exception {
    setupCommandComment("/show --config --scope=" + ReviewScope.FULL.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertTrue(
        changeSetData
            .getReviewSystemMessage()
            .contains(String.format("Invalid option for command `%s`", "SHOW")));
  }

  @Test
  public void commandUnknown() throws Exception {
    String command = "/UNKNOWN";
    setupCommandComment(command);
    setupMockRequestCreateResponse("openAiResponseRequest.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    String systemMessage =
        String.format(
            localizer.getText("message.command.unknown"),
            "@" + GERRIT_AI_USERNAME + " " + command);
    Assert.assertEquals(systemMessage, changeSetData.getReviewSystemMessage());
  }

  private void assertOnlyScopedShowBlock(
      String systemMessage, String titlesResource, ReviewScope reviewScope) {
    List<String> titles = List.of(readTestFile(titlesResource).split("\\R"));
    String includedTitle = titles.get(reviewScopeTitleIndex(reviewScope));
    for (String title : titles) {
      if (title.equals(includedTitle)) {
        Assert.assertTrue(systemMessage.contains(title));
      } else {
        Assert.assertFalse(systemMessage.contains(title));
      }
    }
    String codeFence = TextUtils.CODE_DELIMITER;
    Assert.assertTrue(systemMessage.contains(codeFence + "\n" + includedTitle));
    Assert.assertEquals(2, systemMessage.split(codeFence, -1).length - 1);
  }

  private int reviewScopeTitleIndex(ReviewScope reviewScope) {
    return switch (reviewScope) {
      case FULL -> 0;
      case PATCHSET -> 1;
      case COMMIT_MESSAGE -> 2;
    };
  }
}
