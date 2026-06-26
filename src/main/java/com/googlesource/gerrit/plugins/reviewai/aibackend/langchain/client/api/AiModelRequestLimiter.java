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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AiModelRequestLimiter {
  private static final AdjustableSemaphore SEMAPHORE = new AdjustableSemaphore();

  private AiModelRequestLimiter() {}

  public static ChatResponse chat(Configuration config, ChatModel model, ChatRequest request) {
    return execute(config, () -> model.chat(request));
  }

  public static ChatResponse chat(
      Configuration config, ChatModel model, List<ChatMessage> messages) {
    return execute(config, () -> model.chat(messages));
  }

  static <T> T execute(Configuration config, Supplier<T> supplier) {
    int maxConcurrentRequests = config == null ? 0 : config.getAiMaxConcurrentRequests();
    if (maxConcurrentRequests <= 0) {
      return supplier.get();
    }

    SEMAPHORE.resize(maxConcurrentRequests);
    boolean acquired = false;
    try {
      SEMAPHORE.acquire();
      acquired = true;
      log.debug(
          "Acquired AI model request permit: active={}, max={}",
          maxConcurrentRequests - SEMAPHORE.availablePermits(),
          maxConcurrentRequests);
      return supplier.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for AI model request permit", e);
    } finally {
      if (acquired) {
        SEMAPHORE.release();
      }
    }
  }

  private static class AdjustableSemaphore extends Semaphore {
    private int maxPermits = 0;

    AdjustableSemaphore() {
      super(0, true);
    }

    synchronized void resize(int newMaxPermits) {
      if (newMaxPermits == maxPermits) {
        return;
      }
      int delta = newMaxPermits - maxPermits;
      if (delta > 0) {
        release(delta);
      } else {
        reducePermits(-delta);
      }
      maxPermits = newMaxPermits;
    }
  }
}
