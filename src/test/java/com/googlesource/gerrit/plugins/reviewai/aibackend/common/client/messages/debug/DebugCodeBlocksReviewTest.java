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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;
import org.junit.Test;

public class DebugCodeBlocksReviewTest {
  private static final String REPLY_RESOURCE = "__files/debug/replyWithPrivateData.json";
  private static final String EXPECTED_OUTPUT_RESOURCE = "__files/debug/replyDebugBlock.txt";

  @Test
  public void debugOutputPreservesApprovedReplyDetails() throws IOException {
    AiReplyItem replyItem = getGson().fromJson(readResource(REPLY_RESOURCE), AiReplyItem.class);

    assertEquals(
        readResource(EXPECTED_OUTPUT_RESOURCE).stripTrailing(),
        formatter().getDebugCodeBlock(replyItem, true));
  }

  @Test
  public void debugOutputDoesNotExposeAdditionalPrivateFields() throws IOException {
    ReplyWithPrivateData replyItem =
        getGson().fromJson(readResource(REPLY_RESOURCE), ReplyWithPrivateData.class);
    String output = formatter().getDebugCodeBlock(replyItem, true);

    assertNotNull(replyItem.privateDebugData);
    assertFalse(output.contains(replyItem.privateDebugData));
    assertEquals(readResource(EXPECTED_OUTPUT_RESOURCE).stripTrailing(), output);
  }

  private static DebugCodeBlocksReview formatter() {
    Configuration config = mock(Configuration.class);
    when(config.getLocaleDefault()).thenReturn(Locale.ENGLISH);
    return new DebugCodeBlocksReview(new Localizer(config));
  }

  private static String readResource(String resource) throws IOException {
    return Files.readString(TestResourceLoader.getTestResourcePath().resolve(resource));
  }

  private static class ReplyWithPrivateData extends AiReplyItem {
    private String privateDebugData;

    private ReplyWithPrivateData() {
      super(AiReplyItem.builder());
    }
  }
}
