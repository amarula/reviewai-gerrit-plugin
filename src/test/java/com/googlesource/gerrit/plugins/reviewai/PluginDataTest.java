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

import static org.junit.Assert.*;
import static org.mockito.Mockito.when;

import java.nio.file.Files;

import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAgentRequestStatusStore;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class PluginDataTest extends TestBase {

  @Before
  public void setUp() {
    setupPluginData();

    // Mock the PluginData annotation global behavior
    when(mockPluginDataPath.resolve("global.data")).thenReturn(realPluginDataPath);
    when(mockPluginDataPath.resolve(CHANGE_ID + ".data"))
        .thenReturn(tempFolder.getRoot().toPath().resolve(CHANGE_ID + ".data"));
  }

  @Test
  public void testValueSetAndGet() {
    PluginDataHandlerProvider provider =
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange());
    PluginDataHandler globalHandler = provider.getGlobalScope();
    PluginDataHandler projectHandler = provider.getProjectScope();

    String key = "testKey";
    String value = "testValue";

    // Test set value
    globalHandler.setValue(key, value);
    projectHandler.setValue(key, value);

    // Test get value
    assertEquals(
        "The value retrieved should match the value set.", value, globalHandler.getValue(key));
    assertEquals(
        "The value retrieved should match the value set.", value, projectHandler.getValue(key));
  }

  @Test
  public void testRemoveValue() {
    PluginDataHandlerProvider provider =
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange());
    PluginDataHandler handler = provider.getGlobalScope();

    String key = "testKey";
    String value = "testValue";

    // Set a value to ensure it can be removed
    handler.setValue(key, value);
    // Remove the value
    handler.removeValue(key);

    // Verify the value is no longer available
    assertNull("The value should be null after being removed.", handler.getValue(key));
  }

  @Test
  public void testCreateFileOnNonexistent() throws Exception {
    // Ensure the file doesn't exist before creating the handler
    Files.deleteIfExists(realPluginDataPath);

    PluginDataHandlerProvider provider =
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange());
    provider.getGlobalScope();

    // The constructor should create the file if it doesn't exist
    assertTrue(
        "The config file should exist after initializing the handler.",
        Files.exists(realPluginDataPath));
  }

  @Test
  public void testHandlersForSameScopeShareWrites() {
    PluginDataHandlerProvider provider =
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange());
    PluginDataHandler firstHandler = provider.getChangeScope();
    PluginDataHandler secondHandler = provider.getChangeScope();

    firstHandler.setValue("firstKey", "firstValue");
    secondHandler.setValue("secondKey", "secondValue");

    assertEquals("firstValue", firstHandler.getValue("firstKey"));
    assertEquals("secondValue", firstHandler.getValue("secondKey"));
    assertEquals("firstValue", secondHandler.getValue("firstKey"));
    assertEquals("secondValue", secondHandler.getValue("secondKey"));
  }

  @Test
  public void testHandlersFromDifferentProvidersMergeWritesToSameFile() throws Exception {
    PluginDataHandlerProvider firstProvider =
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange());
    PluginDataHandlerProvider secondProvider =
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange());
    PluginDataHandler firstHandler = firstProvider.getChangeScope();
    PluginDataHandler secondHandler = secondProvider.getChangeScope();

    firstHandler.setValue("conversationId.review_code", "review-code-conversation");
    secondHandler.setValue("dynamicConfig", "{\"selectedAiModel\":\"OpenAI/gpt-5.4-mini\"}");

    assertEquals("review-code-conversation", firstHandler.getValue("conversationId.review_code"));
    assertEquals(
        "{\"selectedAiModel\":\"OpenAI/gpt-5.4-mini\"}",
        firstHandler.getValue("dynamicConfig"));
    String dataFile = Files.readString(tempFolder.getRoot().toPath().resolve(CHANGE_ID + ".data"));
    assertTrue(dataFile.contains("conversationId.review_code=review-code-conversation"));
    assertTrue(dataFile.contains("dynamicConfig="));
  }

  @Test
  public void testReviewAgentPendingRequestResolutionFollowsMovedRequest() {
    PluginDataHandlerProvider provider =
        new PluginDataHandlerProvider(mockPluginDataPath, getGerritChange());
    ReviewAgentRequestStatusStore statusStore =
        new ReviewAgentRequestStatusStore(provider.getChangeScope());

    statusStore.pending("request-1", "/review");
    String initialRequestId = statusStore.getLatestPendingRequestId().orElseThrow();
    statusStore.move("request-1", "message-1");

    assertEquals(
        "message-1", statusStore.getPendingRequestId(initialRequestId).orElseThrow());
  }
}
