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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PluginChatMemoryStoreTest {
  private static final String USER_MESSAGE_RESOURCE = "__files/langchain/chatMemoryUserMessage.txt";
  private static final String AI_MESSAGE_RESOURCE = "__files/langchain/chatMemoryAiMessage.txt";

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Test
  public void persistsMessagesInH2Store() throws Exception {
    Path pluginDataDir = tempFolder.newFolder("plugin-data").toPath();
    PluginChatMemoryStore store = new PluginChatMemoryStore(newTestReviewAiDb(pluginDataDir));
    LangChainMemoryId memoryId = new LangChainMemoryId("change-1", 3, "review_code");
    String userMessage = readResource(USER_MESSAGE_RESOURCE);
    String aiMessage = readResource(AI_MESSAGE_RESOURCE);

    store.updateMessages(memoryId, List.of(UserMessage.from(userMessage), AiMessage.from(aiMessage)));

    List<ChatMessage> restored = store.getMessages(memoryId);

    assertEquals(2, restored.size());
    assertEquals(userMessage, ((UserMessage) restored.get(0)).singleText());
    assertEquals(aiMessage, ((AiMessage) restored.get(1)).text());
    assertTrue(Files.exists(pluginDataDir.resolve("reviewai.mv.db")));
    try (Stream<Path> files = Files.list(pluginDataDir)) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".data")));
    }
  }

  @Test
  public void deletesMessagesByMemoryId() throws Exception {
    Path pluginDataDir = tempFolder.newFolder("plugin-data").toPath();
    PluginChatMemoryStore store = new PluginChatMemoryStore(newTestReviewAiDb(pluginDataDir));
    LangChainMemoryId memoryId = new LangChainMemoryId("change-1", 3, "review_code");

    store.updateMessages(memoryId, List.of(UserMessage.from(readResource(USER_MESSAGE_RESOURCE))));
    store.deleteMessages(memoryId);

    assertTrue(store.getMessages(memoryId).isEmpty());
  }

  @Test
  public void returnsMessagesOnlyFromRequestedScope() throws Exception {
    String jdbcUrl = "jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    PluginChatMemoryStore store = new PluginChatMemoryStore(jdbcUrl);
    LangChainMemoryId reviewCode = new LangChainMemoryId("change-1", 3, "review_code");
    LangChainMemoryId reviewCommitMessage =
        new LangChainMemoryId("change-1", 3, "review_commit_message");
    String userMessage = readResource(USER_MESSAGE_RESOURCE);
    String aiMessage = readResource(AI_MESSAGE_RESOURCE);

    store.updateMessages(reviewCode, List.of(UserMessage.from(userMessage)));
    store.updateMessages(
        reviewCommitMessage,
        List.of(UserMessage.from("Commit message review"), AiMessage.from(aiMessage)));

    List<ChatMessage> restored = store.getMessages(reviewCode);

    assertEquals(1, restored.size());
    assertEquals(userMessage, ((UserMessage) restored.get(0)).singleText());
  }

  @Test
  public void deleteMessagesOnlyRemovesCurrentScope() throws Exception {
    String jdbcUrl = "jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    PluginChatMemoryStore store = new PluginChatMemoryStore(jdbcUrl);
    LangChainMemoryId reviewCode = new LangChainMemoryId("change-1", 3, "review_code");
    LangChainMemoryId requests = new LangChainMemoryId("change-1", 3, "requests");
    String userMessage = readResource(USER_MESSAGE_RESOURCE);
    String aiMessage = readResource(AI_MESSAGE_RESOURCE);

    store.updateMessages(reviewCode, List.of(UserMessage.from(userMessage)));
    store.updateMessages(
        requests, List.of(UserMessage.from(userMessage), AiMessage.from(aiMessage)));

    store.deleteMessages(requests);

    List<ChatMessage> restored = store.getMessages(reviewCode);
    assertEquals(1, restored.size());
    assertEquals(userMessage, ((UserMessage) restored.get(0)).singleText());
  }

  @Test
  public void deletesAllScopesForChangeSet() throws Exception {
    String jdbcUrl = "jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
    PluginChatMemoryStore store = new PluginChatMemoryStore(jdbcUrl);
    LangChainMemoryId reviewCode = new LangChainMemoryId("change-1", 3, "review_code");
    LangChainMemoryId requests = new LangChainMemoryId("change-1", 3, "requests");
    LangChainMemoryId otherPatchSet = new LangChainMemoryId("change-1", 4, "review_code");
    String userMessage = readResource(USER_MESSAGE_RESOURCE);
    String aiMessage = readResource(AI_MESSAGE_RESOURCE);

    store.updateMessages(reviewCode, List.of(UserMessage.from(userMessage)));
    store.updateMessages(
        requests, List.of(UserMessage.from(userMessage), AiMessage.from(aiMessage)));
    store.updateMessages(otherPatchSet, List.of(UserMessage.from(userMessage)));

    store.deleteMessagesForChangeSet("change-1", 3);

    assertTrue(store.getMessages(reviewCode).isEmpty());
    assertTrue(store.getMessages(requests).isEmpty());
    assertFalse(store.getMessages(otherPatchSet).isEmpty());
  }

  private String readResource(String resourceName) throws Exception {
    URL resource = getClass().getClassLoader().getResource(resourceName);
    return Files.readString(Paths.get(resource.toURI())).trim();
  }

  private ReviewAiDb newTestReviewAiDb(Path pluginDataDir) throws Exception {
    return new ReviewAiDb(
        pluginDataDir,
        "jdbc:h2:"
            + pluginDataDir.resolve("reviewai").toAbsolutePath().normalize()
            + ";AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1");
  }
}
