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

import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.extensions.restapi.RestApiException;
import com.google.gerrit.json.OutputFormat;
import com.google.gson.Gson;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.listener.EventHandlerTask;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.OpenAiReviewTestBase;
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
  public void commandConfigure() throws Exception {
    String dynamicKey = "aiModel";
    String dynamicValue = "DUMMY_MODEL";
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
    Assert.assertTrue(reviewMessage.contains("PROMPTS CURRENTLY USED"));
    Assert.assertTrue(reviewMessage.contains("Review the following Patch Set:  ` ` `"));
    Assert.assertTrue(reviewMessage.contains("\n```\n"));
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
}
