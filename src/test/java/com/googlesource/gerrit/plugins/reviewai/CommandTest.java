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
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.json.OutputFormat;
import com.google.gson.Gson;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.BaseOptionSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration.AgentSpecializationLevel;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAgentRequestStatusStore;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.listener.EventHandlerTask;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiLangChainReviewTestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.OpenAiUriResourceLocator;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.utils.TextUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.googlesource.gerrit.plugins.reviewai.config.Configuration.KEY_DIRECTIVES;
import static com.googlesource.gerrit.plugins.reviewai.config.Configuration.KEY_SELECTIVE_LOG_LEVEL_OVERRIDE;
import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyBase.CodeContextPolicies;
import static com.googlesource.gerrit.plugins.reviewai.config.dynamic.DynamicConfigManager.KEY_DYNAMIC_CONFIG;
import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.TemplateUtils.renderTemplate;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.sortTextLines;
import static org.mockito.Mockito.when;

public class CommandTest extends OpenAiLangChainReviewTestBase {
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
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange(), getTestReviewAiDb());
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
  public void commandReviewDoesNotStoreAutomaticPatchSetConversationTurn()
      throws RestApiException {
    setupCommandComment("/review");
    setupMockRequestCreateResponse("openAiResponseRequest.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Mockito.verify(reviewAgentConversationStore, Mockito.never())
        .appendTurn(
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.anyString(),
            Mockito.any(),
            Mockito.any());
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
        systemMessage.contains("`/suggest [--scope=patchset|commit_message]`"));
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
  public void commandHelpSpecificSuggestCommand() throws Exception {
    setupCommandComment("/help /suggest");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    String systemMessage = changeSetData.getReviewSystemMessage();
    Assert.assertEquals(false, changeSetData.getForcedReview());
    Assert.assertTrue(systemMessage.contains("HELP FOR `/suggest`"));
    Assert.assertTrue(
        systemMessage.contains("`/suggest [--scope=patchset|commit_message]`"));
    Assert.assertTrue(systemMessage.contains("suggested edits"));
  }

  @Test
  public void commandHelpRejectsUnknownCommandWithWarning() throws Exception {
    setupCommandComment("/help /INVALID_COMMAND");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer, "message.command.help.command.unknown", "/INVALID_COMMAND"),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandReviewCommitMessageScopeIgnoresCommitMessageReviewConfig() throws Exception {
    when(globalConfig.getBoolean(Mockito.eq("aiReviewCommitMessages"), Mockito.anyBoolean()))
        .thenReturn(false);

    setupCommandComment(reviewCommandWithScope(ReviewScope.COMMIT_MESSAGE));
    setupMockRequestCreateResponse("openAiResponseRequest.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    testRequestSent();
    Assert.assertTrue(getInputContent().contains("Minor fixes"));
    Assert.assertTrue(getInputContent().contains("diff --git"));
  }

  @Test
  public void commandReviewPatchsetScopeExcludesCommitMessage() throws Exception {
    setupCommandComment(reviewCommandWithScope(ReviewScope.PATCHSET));
    setupMockRequestCreateResponse("openAiResponseRequest.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    testRequestSent();
    Assert.assertFalse(getInputContent().contains("Subject: Minor fixes"));
    Assert.assertTrue(getInputContent().contains("diff --git"));
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
        "ReviewAI Message: No update to show for this Change Set", captor.getValue().message);
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
        "ReviewAI Message: No update to show for this Change Set",
        status.responseText);
  }

  @Test
  public void commandReviewDoesNotCompletePendingReviewAgentStatusForAiAuthoredMessage()
      throws Exception {
    ReviewAgentRequestStatusStore statusStore =
        new ReviewAgentRequestStatusStore(getChangeDataHandler());
    statusStore.pending("request-1", "/review");
    eventAccountId = GERRIT_AI_ACCOUNT_ID;
    eventAccountName = GERRIT_AI_USERNAME;
    eventAccountEmail = config.getGerritUserEmail();
    eventAccountUsername = GERRIT_AI_USERNAME;
    mockGerritChangeCommentsApiCall(Map.of());

    EventHandlerTask.Result result =
        handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ReviewAgentRequestStatusStore.RequestStatus status = statusStore.get("request-1");
    Assert.assertEquals(EventHandlerTask.Result.NOT_SUPPORTED, result);
    Assert.assertEquals(ReviewAgentRequestStatusStore.STATUS_PENDING, status.status);
    Assert.assertNull(status.responseText);
  }

  @Test
  public void commandReviewCompletesPendingReviewAgentStatusWithOpenAiConnectionError()
      throws Exception {
    ReviewAgentRequestStatusStore statusStore =
        new ReviewAgentRequestStatusStore(getChangeDataHandler());
    statusStore.pending("request-1", "/review");
    setupCommandComment("/review");
    WireMock.stubFor(
        WireMock.post(WireMock.urlEqualTo(OpenAiUriResourceLocator.responsesUri()))
            .willReturn(WireMock.aResponse().withStatus(400)));

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ReviewAgentRequestStatusStore.RequestStatus status = statusStore.get("request-1");
    Assert.assertEquals(ReviewAgentRequestStatusStore.STATUS_COMPLETED, status.status);
    Assert.assertEquals(
        "ReviewAI **ERROR**: Unable to connect to AI server", status.responseText);
  }

  @Test
  public void commandReviewScopeRejectsUnsupportedValue() throws Exception {
    setupCommandComment("/review --scope=full");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer,
            "message.command.option.value.invalid",
            "SCOPE",
            "full",
            ReviewScope.reviewCommandOptionValues()),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandSuggestScopeRejectsUnsupportedValue() throws Exception {
    setupCommandComment("/suggest --scope=full");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer,
            "message.command.option.value.invalid",
            "SCOPE",
            "full",
            ReviewScope.reviewCommandOptionValues()),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandSuggestPatchsetScopeShowsSuggestion() throws Exception {
    setupCommandComment("/suggest --scope=patchset");
    setupSuggestResponses("openAiPatchSetSuggestionResponse.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    ReviewInput.CommentInput suggestion =
        captor.getValue().comments.get("test_file_1.py").get(0);
    Assert.assertEquals(
        readTestFile("__files/langchain/suggestPatchSetFixReply.txt").strip(),
        suggestion.message);
    Assert.assertNotNull(suggestion.range);
    Assert.assertNull(captor.getValue().message);
  }

  @Test
  public void commandSuggestCommitMessageScopeShowsSuggestion() throws Exception {
    setupCommandComment("/suggest --scope=commit_message");
    setupSuggestResponses("openAiCommitMessageSuggestionResponse.json");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    ReviewInput.CommentInput suggestion =
        captor.getValue().comments.get("/COMMIT_MSG").get(0);
    Assert.assertEquals(
        readTestFile("__files/langchain/suggestCommitMessageReply.txt").strip(),
        suggestion.message);
    Assert.assertNotNull(suggestion.range);
    Assert.assertEquals(7, suggestion.range.startLine);
    Assert.assertEquals(12, suggestion.range.endLine);
    Assert.assertNull(captor.getValue().message);
  }

  private void setupSuggestResponses(String suggestionResponse) {
    setupMockRequestCreateResponse(
        "openAiNegativeReviewResponse.json", Scenario.STARTED, "initial-review-complete");
    setupMockRequestCreateResponse(
        suggestionResponse, "initial-review-complete", "suggestion-complete");
  }

  @Test
  public void commandReviewRejectsUnknownOptionWithWarning() throws Exception {
    setupCommandComment("/review --INVALID_PARAM");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer,
            "message.command.option.unknown",
            CommandSet.REVIEW,
            Map.of("INVALID_PARAM", "")),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandReviewRejectsInvalidBaseOptionWithWarning() throws Exception {
    setupCommandComment("/review --reset");

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer,
            "message.command.option.invalid",
            CommandSet.REVIEW,
            Map.of(BaseOptionSet.RESET, "")),
        changeSetData.getReviewSystemMessage());
  }

  private String reviewCommandWithScope(ReviewScope reviewScope) {
    return "/review --scope=" + reviewScope.getCommandOptionValue();
  }

  private String positiveReviewResponse() {
    return readTestFile(RESOURCE_OPENAI_PATH + "openAiPositiveReviewResponse.json");
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
  public void commandConfigureSelectivelyResetsListSetting() throws Exception {
    setupCommandComment("/configure --reset --" + KEY_SELECTIVE_LOG_LEVEL_OVERRIDE);
    enableMessageDebugging();
    PluginDataHandler changeHandler = getChangeDataHandler();
    changeHandler.setJsonValue(
        KEY_DYNAMIC_CONFIG,
        Map.of(
            KEY_SELECTIVE_LOG_LEVEL_OVERRIDE,
            getGson().toJson(List.of("ClientMessage")),
            "aiModel",
            "OpenAI/gpt-4.1"));

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertEquals(
        getGson().toJson(Map.of("aiModel", "OpenAI/gpt-4.1")),
        changeHandler.getValue(KEY_DYNAMIC_CONFIG));
  }

  @Test
  public void commandConfigureRejectsInvalidCodeContextPolicy() throws Exception {
    String invalidValue = "INVALID";
    setupCommandComment("/configure --codeContextPolicy=" + invalidValue);
    enableMessageDebugging();
    PluginDataHandler changeHandler = getChangeDataHandler();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertNull(changeHandler.getValue(KEY_DYNAMIC_CONFIG));
    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer,
            "message.command.option.value.invalid",
            "codeContextPolicy",
            invalidValue,
            List.of(CodeContextPolicies.values())),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandConfigureRejectsInvalidAgentSpecializationLevel() throws Exception {
    String invalidValue = "INVALID";
    setupCommandComment("/configure --agentSpecializationLevel=" + invalidValue);
    enableMessageDebugging();
    PluginDataHandler changeHandler = getChangeDataHandler();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertNull(changeHandler.getValue(KEY_DYNAMIC_CONFIG));
    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer,
            "message.command.option.value.invalid",
            "agentSpecializationLevel",
            invalidValue,
            List.of(AgentSpecializationLevel.values())),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandConfigureRejectsUnknownSettingWithWarning() throws Exception {
    setupCommandComment("/configure --unknownSetting=value");
    enableMessageDebugging();
    PluginDataHandler changeHandler = getChangeDataHandler();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertNull(changeHandler.getValue(KEY_DYNAMIC_CONFIG));
    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer, "message.command.option.config.unknown", "unknownSetting"),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandConfigureRejectsMalformedListWithWarning() throws Exception {
    setupCommandComment("/configure --aiModels=not-an-array");
    enableMessageDebugging();
    PluginDataHandler changeHandler = getChangeDataHandler();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertNull(changeHandler.getValue(KEY_DYNAMIC_CONFIG));
    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer, "message.command.option.config.array.malformed", "aiModels"),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandConfigureValidCodeContextPolicyClearsOldInvalidValueWarning() throws Exception {
    when(projectConfig.getString("codeContextPolicy")).thenReturn("INVALID");
    Assert.assertEquals(CodeContextPolicies.ON_DEMAND, config.getCodeContextPolicy());
    setupCommandComment("/configure --codeContextPolicy=NONE");
    enableMessageDebugging();
    PluginDataHandler changeHandler = getChangeDataHandler();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertEquals(
        getGson().toJson(Map.of("codeContextPolicy", "NONE")),
        changeHandler.getValue(KEY_DYNAMIC_CONFIG));
    Assert.assertTrue(config.getUnknownEnumSettings().isEmpty());
    ArgumentCaptor<ReviewInput> captor = testRequestSent();
    Assert.assertFalse(captor.getValue().message.contains("**WARNING**"));
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
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange(), getTestReviewAiDb());
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
    Assert.assertTrue(reviewMessage.contains("ReviewAI Message:"));
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
  public void commandShowPromptsSuggestModeIncludesOnlySuggestPrompt() throws Exception {
    setupCommandComment("/show --prompts --mode=suggest");
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    String systemMessage = changeSetData.getReviewSystemMessage();
    List<String> expectedTitles =
        List.of(readTestFile("__files/commands/showSuggestPromptsTitles.txt").split("\\R"));
    for (String expectedTitle : expectedTitles) {
      Assert.assertTrue(systemMessage.contains(expectedTitle));
    }
    List<String> reviewTitles =
        List.of(readTestFile("__files/commands/showPromptsTitles.txt").split("\\R"));
    for (String reviewTitle : reviewTitles) {
      Assert.assertFalse(systemMessage.contains(reviewTitle));
    }
    Assert.assertTrue(systemMessage.contains("Generate Gerrit suggested edits"));
    Assert.assertTrue(systemMessage.contains("every negative review reply"));
    Assert.assertTrue(systemMessage.contains("diff --git a/test_file_1.py b/test_file_1.py"));
    Assert.assertEquals(2, systemMessage.split(TextUtils.CODE_DELIMITER, -1).length - 1);
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
  public void commandShowInstructionsSuggestModeIncludesAllSuggestScopes()
      throws Exception {
    setupCommandComment("/show --instructions --mode=suggest");
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    String systemMessage = changeSetData.getReviewSystemMessage();
    List<String> expectedTitles =
        List.of(readTestFile("__files/commands/showSuggestInstructionsTitles.txt").split("\\R"));
    for (String expectedTitle : expectedTitles) {
      Assert.assertTrue(systemMessage.contains(expectedTitle));
    }
    List<String> reviewTitles =
        List.of(readTestFile("__files/commands/showInstructionsTitles.txt").split("\\R"));
    for (String reviewTitle : reviewTitles) {
      Assert.assertFalse(systemMessage.contains(reviewTitle));
    }
    Assert.assertTrue(
        systemMessage.contains(
            readTestFile("__files/commands/showSuggestInstructionsPatchSetText.txt").strip()));
    Assert.assertTrue(
        systemMessage.contains(
            readTestFile("__files/commands/showSuggestInstructionsCommitMessageText.txt")
                .strip()));
    Assert.assertEquals(6, systemMessage.split(TextUtils.CODE_DELIMITER, -1).length - 1);
  }

  @Test
  public void commandShowInstructionsSuggestModeFullScopeIncludesOnlyFullInstructions()
      throws Exception {
    setupCommandComment(
        "/show --instructions --mode=suggest --scope="
            + ReviewScope.FULL.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    assertOnlyScopedSuggestShowBlock(
        changeSetData.getReviewSystemMessage(), ReviewScope.FULL);
    Assert.assertTrue(
        changeSetData
            .getReviewSystemMessage()
            .contains(
                readTestFile("__files/commands/showSuggestInstructionsPatchSetText.txt").strip()));
    Assert.assertTrue(
        changeSetData
            .getReviewSystemMessage()
            .contains(
                readTestFile("__files/commands/showSuggestInstructionsCommitMessageText.txt")
                    .strip()));
  }

  @Test
  public void commandShowInstructionsSuggestModePatchSetScopeIncludesOnlyPatchSetInstructions()
      throws Exception {
    setupCommandComment(
        "/show --instructions --mode=suggest --scope="
            + ReviewScope.PATCHSET.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    assertOnlyScopedSuggestShowBlock(
        changeSetData.getReviewSystemMessage(), ReviewScope.PATCHSET);
    Assert.assertTrue(
        changeSetData
            .getReviewSystemMessage()
            .contains(
                readTestFile("__files/commands/showSuggestInstructionsPatchSetText.txt").strip()));
    Assert.assertFalse(
        changeSetData
            .getReviewSystemMessage()
            .contains(
                readTestFile("__files/commands/showSuggestInstructionsCommitMessageText.txt")
                    .strip()));
  }

  @Test
  public void commandShowInstructionsSuggestCommitMessageScopeIncludesOnlyCommitMessage()
      throws Exception {
    setupCommandComment(
        "/show --instructions --mode=suggest --scope="
            + ReviewScope.COMMIT_MESSAGE.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    assertOnlyScopedSuggestShowBlock(
        changeSetData.getReviewSystemMessage(), ReviewScope.COMMIT_MESSAGE);
    Assert.assertFalse(
        changeSetData
            .getReviewSystemMessage()
            .contains(
                readTestFile("__files/commands/showSuggestInstructionsPatchSetText.txt").strip()));
    Assert.assertTrue(
        changeSetData
            .getReviewSystemMessage()
            .contains(
                readTestFile("__files/commands/showSuggestInstructionsCommitMessageText.txt")
                    .strip()));
  }

  @Test
  public void commandShowScopeWithoutPromptsOrInstructionsIsRejected() throws Exception {
    setupCommandComment("/show --config --scope=" + ReviewScope.FULL.getCommandOptionValue());
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Map<BaseOptionSet, String> options = new HashMap<>();
    options.put(BaseOptionSet.CONFIG, "");
    options.put(BaseOptionSet.SCOPE, ReviewScope.FULL.getCommandOptionValue());
    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer, "message.command.option.invalid", CommandSet.SHOW, options),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandShowModeWithoutPromptsOrInstructionsIsRejected() throws Exception {
    setupCommandComment("/show --config --mode=suggest");
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Map<BaseOptionSet, String> options = new HashMap<>();
    options.put(BaseOptionSet.CONFIG, "");
    options.put(BaseOptionSet.MODE, "suggest");
    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer, "message.command.option.invalid", CommandSet.SHOW, options),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandShowRejectsUnsupportedMode() throws Exception {
    setupCommandComment("/show --prompts --mode=review");
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer,
            "message.command.option.value.invalid",
            BaseOptionSet.MODE,
            "review",
            List.of("suggest")),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void commandShowRejectsMissingRequiredOptionWithWarning() throws Exception {
    setupCommandComment("/show");
    enableMessageDebugging();

    handleEventBasedOnType(EventHandlerTask.SupportedEvents.COMMENT_ADDED);

    Assert.assertEquals(
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer, "message.command.option.required", CommandSet.SHOW),
        changeSetData.getReviewSystemMessage());
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

  private void assertOnlyScopedSuggestShowBlock(String systemMessage, ReviewScope reviewScope) {
    List<String> titles =
        List.of(readTestFile("__files/commands/showSuggestInstructionsTitles.txt").split("\\R"));
    String includedTitle =
        switch (reviewScope) {
          case FULL -> titles.get(0);
          case PATCHSET -> titles.get(1);
          case COMMIT_MESSAGE -> titles.get(2);
        };
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
}
