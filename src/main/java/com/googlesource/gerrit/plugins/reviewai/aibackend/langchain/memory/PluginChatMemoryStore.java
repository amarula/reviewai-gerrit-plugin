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

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.data.langchain.LangChainChatMemoryRepository;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class PluginChatMemoryStore implements ChatMemoryStore {
  private static final String DEFAULT_SCOPE = "default";

  private final LangChainChatMemoryRepository repository;

  @Inject
  public PluginChatMemoryStore(LangChainChatMemoryRepository repository) {
    this.repository = repository;
  }

  public PluginChatMemoryStore(ReviewAiDb db) throws SQLException {
    this(new LangChainChatMemoryRepository(db));
  }

  public PluginChatMemoryStore(String jdbcUrl) throws SQLException, IOException {
    this(new ReviewAiDb(Path.of("."), jdbcUrl));
  }

  @Override
  public List<ChatMessage> getMessages(Object memoryId) {
    MemoryKey key = MemoryKey.from(memoryId);
    try {
      List<ChatMessage> result = new ArrayList<>();
      for (String json : repository.getMessageJsons(key.changeId(), key.patchSet(), key.scope())) {
        if (json != null && !json.isEmpty()) {
          result.add(ChatMessageDeserializer.messageFromJson(json));
        }
      }
      log.info(
          "Loaded {} chat messages from LangChain memory store for {}",
          result.size(),
          memoryId);
      return result;
    } catch (Exception e) {
      log.warn("Failed to get chat memory messages for {}; returning empty list", memoryId, e);
      return new ArrayList<>();
    }
  }

  @Override
  public void updateMessages(Object memoryId, List<ChatMessage> messages) {
    MemoryKey key = MemoryKey.from(memoryId);
    try {
      if (messages == null || messages.isEmpty()) {
        deleteMessages(memoryId);
        return;
      }
      int messagesAppended =
          repository.updateMessages(
              key.changeId(),
              key.patchSet(),
              key.scope(),
              messages.stream().map(ChatMessageSerializer::messageToJson).toList());
      log.info(
          "Persisted {} new chat messages into LangChain memory store for {}",
          messagesAppended,
          memoryId);
    } catch (Exception e) {
      log.warn("Failed to persist chat memory messages for {}", memoryId, e);
    }
  }

  @Override
  public void deleteMessages(Object memoryId) {
    MemoryKey key = MemoryKey.from(memoryId);
    log.info("Clearing LangChain memory store for {}", memoryId);
    try {
      repository.deleteMessages(key.changeId(), key.patchSet(), key.scope());
    } catch (Exception e) {
      log.warn("Failed to clear chat memory messages for {}", memoryId, e);
    }
  }

  public void deleteMessagesForChangeSet(String changeId, int patchSet) {
    log.info(
        "Clearing LangChain memory store for change {} patch set {}", changeId, patchSet);
    try {
      repository.deleteMessagesForChangeSet(changeId, patchSet);
    } catch (Exception e) {
      log.warn(
          "Failed to clear chat memory messages for change {} patch set {}",
          changeId,
          patchSet,
          e);
    }
  }

  private record MemoryKey(String changeId, int patchSet, String scope) {
    private static MemoryKey from(Object memoryId) {
      if (memoryId instanceof LangChainMemoryId id) {
        return new MemoryKey(id.getChangeId(), id.getPatchSet(), id.getScope());
      }
      return new MemoryKey(String.valueOf(memoryId), 0, DEFAULT_SCOPE);
    }
  }
}
