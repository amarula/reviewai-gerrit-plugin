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

package com.googlesource.gerrit.plugins.reviewai.aibackend.mock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.utils.FileUtils;
import com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class MockAiClientTest {
  private static final String BEHAVIOR_FILE = "__files/mock/mockAiModelBehavior.json";
  private static final String JSON_RESPONSE_BEHAVIOR_FILE =
      "__files/mock/mockAiModelJsonResponseBehavior.json";
  private static final String PACKAGED_JSON_RESPONSE_EXAMPLE =
      "config/mockAiModelJsonResponse.json";

  @Mock private Configuration config;

  @Test
  public void returnsConfiguredResponseAfterDelay() throws Exception {
    when(config.getMockAiModelConfigPath()).thenReturn(BEHAVIOR_FILE);
    MockAiModelBehavior behavior =
        GsonUtils.jsonToClass(
            FileUtils.getInputStreamReader(BEHAVIOR_FILE), MockAiModelBehavior.class);
    MockAiClient client = new MockAiClient(config);

    long start = System.nanoTime();
    AiResponseContent response = client.ask(null, null, "");
    long elapsedMs = (System.nanoTime() - start) / 1_000_000;

    assertTrue(elapsedMs >= 1);
    assertEquals("", response.getMessageContent());
    assertEquals(1, response.getReplies().size());
    assertEquals(behavior.getResponseText(), response.getReplies().getFirst().getReply());
    assertEquals(behavior.getScore(), response.getReplies().getFirst().getScore());
    assertTrue(client.getRequestBody().contains(behavior.getResponseText()));
  }

  @Test
  public void returnsDirectJsonResponseWhenConfigured() throws Exception {
    when(config.getMockAiModelConfigPath()).thenReturn(JSON_RESPONSE_BEHAVIOR_FILE);
    MockAiModelBehavior behavior =
        GsonUtils.jsonToClass(
            FileUtils.getInputStreamReader(JSON_RESPONSE_BEHAVIOR_FILE),
            MockAiModelBehavior.class);
    MockAiClient client = new MockAiClient(config);

    AiResponseContent response = client.ask(null, null, "");

    assertEquals(behavior.getJsonResponse().getMessageContent(), response.getMessageContent());
    assertEquals(behavior.getJsonResponse().getChangeId(), response.getChangeId());
    assertNull(response.getReplies());
  }

  @Test
  public void packagedJsonResponseExampleReturnsMultipleReplies() throws Exception {
    when(config.getMockAiModelConfigPath()).thenReturn(PACKAGED_JSON_RESPONSE_EXAMPLE);
    MockAiClient client = new MockAiClient(config);

    AiResponseContent response = client.ask(null, null, "");

    assertEquals("mock-json-response", response.getChangeId());
    assertEquals("", response.getMessageContent());
    assertEquals(2, response.getReplies().size());
    assertEquals(Double.valueOf(-1), response.getReplies().getFirst().getScore());
    assertEquals(Double.valueOf(-0.5), response.getReplies().get(1).getScore());
  }
}
