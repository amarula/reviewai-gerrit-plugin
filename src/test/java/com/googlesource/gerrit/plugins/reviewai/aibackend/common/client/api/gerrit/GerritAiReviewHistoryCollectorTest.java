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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import com.google.gson.reflect.TypeToken;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.web.model.AiReviewHistoryInfo;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class GerritAiReviewHistoryCollectorTest {
  private static final Path TEST_RESOURCES_PATH = Paths.get("src/test/resources");
  private static final String FIXTURE_PATH = "__files/aibackend/common/client/api/gerrit/";
  private static final Type COMMENTS_BY_FILE_TYPE =
      new TypeToken<Map<String, List<GerritComment>>>() {}.getType();

  @Test
  public void collectsMessagesFromAndToAiAssistant() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getGerritUserName()).thenReturn("reviewai");
    when(config.getGerritUserEmail()).thenReturn("");

    Localizer localizer = mock(Localizer.class);
    when(localizer.getText("system.message.prefix")).thenReturn("SYSTEM MESSAGE:");
    when(localizer.getText("message.dump.dynamic.configuration.title"))
        .thenReturn("DYNAMIC CONFIGURATION SETTINGS");

    GerritAiReviewHistoryCollector collector = new GerritAiReviewHistoryCollector();
    Map<String, List<GerritComment>> comments =
        readFixture("collectsMessagesFromAndToAiAssistant.json");

    AiReviewHistoryInfo info =
        collector.collect(config, localizer, 7, comments);

    assertEquals(6, info.getEntries().size());

    AiReviewHistoryInfo.Entry patchSetPromptEntry =
        findEntry(info, "please verify the null handling.");
    assertNotNull(patchSetPromptEntry);
    assertEquals("Alice", patchSetPromptEntry.getAuthor());
    assertEquals("user", patchSetPromptEntry.getRole());
    assertNull(patchSetPromptEntry.getFilename());

    AiReviewHistoryInfo.Entry patchSetReviewEntry =
        findEntry(info, "This is the AI review output.");
    assertNotNull(patchSetReviewEntry);
    assertEquals("ReviewAI", patchSetReviewEntry.getAuthor());
    assertEquals("assistant", patchSetReviewEntry.getRole());
    assertNull(patchSetReviewEntry.getFilename());

    AiReviewHistoryInfo.Entry inlinePromptEntry =
        findEntry(info, "can you explain this branch?");
    assertNotNull(inlinePromptEntry);
    assertEquals("Bob", inlinePromptEntry.getAuthor());
    assertEquals("user", inlinePromptEntry.getRole());
    assertEquals("src/main/java/Foo.java", inlinePromptEntry.getFilename());
    assertEquals(Integer.valueOf(42), inlinePromptEntry.getLine());

    AiReviewHistoryInfo.Entry inlineReplyEntry =
        findEntry(info, "This branch is guarding the fallback path.");
    assertNotNull(inlineReplyEntry);
    assertEquals("ReviewAI", inlineReplyEntry.getAuthor());
    assertEquals("assistant", inlineReplyEntry.getRole());
    assertEquals(false, inlineReplyEntry.isSystemMessage());

    AiReviewHistoryInfo.Entry systemReplyEntry =
        findEntry(info, "SYSTEM MESSAGE: No update to show for this Change Set");
    assertNotNull(systemReplyEntry);
    assertEquals("ReviewAI", systemReplyEntry.getAuthor());
    assertEquals("assistant", systemReplyEntry.getRole());
    assertEquals(true, systemReplyEntry.isSystemMessage());

    AiReviewHistoryInfo.Entry commandEntry = findEntry(info, "/review --debug");
    assertNotNull(commandEntry);
    assertEquals("Dave", commandEntry.getAuthor());
    assertEquals("user", commandEntry.getRole());
    assertNull(commandEntry.getFilename());

    assertNull(findEntry(info, "/help"));
    assertNull(findEntry(info, "/show --config"));
  }

  @Test
  public void collectsSystemMessagesEvenWhenPrefixedByPatchSetHeader() {
    Configuration config = mock(Configuration.class);
    when(config.getGerritUserName()).thenReturn("reviewai");
    when(config.getGerritUserEmail()).thenReturn("");

    Localizer localizer = mock(Localizer.class);
    when(localizer.getText("system.message.prefix")).thenReturn("SYSTEM MESSAGE:");
    when(localizer.getText("message.dump.dynamic.configuration.title"))
        .thenReturn("DYNAMIC CONFIGURATION SETTINGS");

    GerritAiReviewHistoryCollector collector = new GerritAiReviewHistoryCollector();

    GerritComment systemReply =
        newComment(
            "msg-system",
            7,
            "ReviewAI",
            "Patch Set 5:\n\nSYSTEM MESSAGE: No update to show for this Change Set",
            "2026-04-09 10:05:00.000000",
            5,
            null,
            null);

    AiReviewHistoryInfo info =
        collector.collect(config, localizer, 7, Map.of("/PATCHSET_LEVEL", List.of(systemReply)));

    assertEquals(1, info.getEntries().size());
    AiReviewHistoryInfo.Entry entry = info.getEntries().get(0);
    assertEquals("assistant", entry.getRole());
    assertEquals(true, entry.isSystemMessage());
    assertEquals("SYSTEM MESSAGE: No update to show for this Change Set", entry.getMessage());
  }

  @Test
  public void collectsDynamicConfigurationMessages() {
    Configuration config = mock(Configuration.class);
    when(config.getGerritUserName()).thenReturn("reviewai");
    when(config.getGerritUserEmail()).thenReturn("");

    Localizer localizer = mock(Localizer.class);
    when(localizer.getText("system.message.prefix")).thenReturn("SYSTEM MESSAGE:");
    when(localizer.getText("message.dump.dynamic.configuration.title"))
        .thenReturn("DYNAMIC CONFIGURATION SETTINGS");

    GerritAiReviewHistoryCollector collector = new GerritAiReviewHistoryCollector();

    GerritComment dynamicConfigReply =
        newComment(
            "msg-dynamic",
            7,
            "ReviewAI",
            "Patch Set 5:\n\n```\nDYNAMIC CONFIGURATION SETTINGS\nfoo: bar\n```",
            "2026-04-09 10:05:00.000000",
            5,
            null,
            null);

    AiReviewHistoryInfo info =
        collector.collect(
            config, localizer, 7, Map.of("/PATCHSET_LEVEL", List.of(dynamicConfigReply)));

    assertEquals(1, info.getEntries().size());
    AiReviewHistoryInfo.Entry entry = info.getEntries().get(0);
    assertEquals("assistant", entry.getRole());
    assertEquals(true, entry.isSystemMessage());
    assertEquals("```\nDYNAMIC CONFIGURATION SETTINGS\nfoo: bar\n```", entry.getMessage());
  }

  @Test
  public void preservesShowCommandSystemMessageBodyInHistory() {
    Configuration config = mock(Configuration.class);
    when(config.getGerritUserName()).thenReturn("reviewai");
    when(config.getGerritUserEmail()).thenReturn("");

    Localizer localizer = mock(Localizer.class);
    when(localizer.getText("system.message.prefix")).thenReturn("SYSTEM MESSAGE:");
    when(localizer.filterProperties("message.dump.", ".title"))
        .thenReturn(List.of("PROMPTS CURRENTLY USED", "INSTRUCTIONS CURRENTLY USED"));

    GerritAiReviewHistoryCollector collector = new GerritAiReviewHistoryCollector();

    GerritComment systemReply =
        newComment(
            "msg-show",
            7,
            "ReviewAI",
            "Patch Set 5:\n\nSYSTEM MESSAGE:\n\n```\nPROMPTS CURRENTLY USED\n\n### Review Prompt\n"
                + "Review the following Patch Set:  ` ` `Subject: <COMMIT_MESSAGE> Change-Id: ..."
                + " <PATCH_SET> ` ` `\n```\n",
            "2026-04-09 10:05:00.000000",
            5,
            null,
            null);

    AiReviewHistoryInfo info =
        collector.collect(config, localizer, 7, Map.of("/PATCHSET_LEVEL", List.of(systemReply)));

    assertEquals(1, info.getEntries().size());
    AiReviewHistoryInfo.Entry entry = info.getEntries().get(0);
    assertEquals("assistant", entry.getRole());
    assertEquals(true, entry.isSystemMessage());
    assertTrue(entry.getMessage().contains("SYSTEM MESSAGE:"));
    assertTrue(entry.getMessage().contains("PROMPTS CURRENTLY USED"));
    assertTrue(entry.getMessage().contains("### Review Prompt"));
    assertTrue(entry.getMessage().contains("Review the following Patch Set:  ` ` `"));
  }

  private static AiReviewHistoryInfo.Entry findEntry(AiReviewHistoryInfo info, String message) {
    return info.getEntries().stream()
        .filter(entry -> message.equals(entry.getMessage()))
        .findFirst()
        .orElse(null);
  }

  private static Map<String, List<GerritComment>> readFixture(String fixtureName)
      throws IOException {
    return getGson()
        .fromJson(
            Files.readString(TEST_RESOURCES_PATH.resolve(FIXTURE_PATH + fixtureName)),
            COMMENTS_BY_FILE_TYPE);
  }

  private static GerritComment newComment(
      String id,
      int accountId,
      String authorName,
      String message,
      String updated,
      Integer patchSet,
      String filename,
      Integer line) {
    GerritComment comment = new GerritComment();
    GerritComment.Author author = new GerritComment.Author();
    author.setAccountId(accountId);
    author.setName(authorName);
    author.setUsername(authorName.toLowerCase());
    comment.setAuthor(author);
    comment.setId(id);
    comment.setMessage(message);
    comment.setUpdated(updated);
    comment.setPatchSet(patchSet);
    comment.setFilename(filename);
    comment.setLine(line);
    return comment;
  }
}
