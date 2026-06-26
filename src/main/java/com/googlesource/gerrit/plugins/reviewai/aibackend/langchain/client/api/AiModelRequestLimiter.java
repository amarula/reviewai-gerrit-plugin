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
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AiModelRequestLimiter {
  private static final RequestGate REQUEST_GATE = new RequestGate();

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

    boolean acquired = false;
    try {
      RequestGate.State state = REQUEST_GATE.acquire(maxConcurrentRequests);
      acquired = true;
      log.debug(
          "Acquired AI model request permit: active={}, max={}",
          state.activeRequests(),
          state.maxConcurrentRequests());
      return supplier.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Interrupted while waiting for AI model request permit", e);
    } finally {
      if (acquired) {
        RequestGate.State state = REQUEST_GATE.release();
        log.debug(
            "Released AI model request permit: active={}, max={}",
            state.activeRequests(),
            state.maxConcurrentRequests());
      }
    }
  }

  private static class RequestGate {
    private int activeRequests = 0;
    private int maxConcurrentRequests = 0;

    synchronized State acquire(int newMaxConcurrentRequests) throws InterruptedException {
      updateMaxConcurrentRequests(newMaxConcurrentRequests);
      while (activeRequests >= maxConcurrentRequests) {
        wait();
      }
      activeRequests++;
      return state();
    }

    synchronized State release() {
      if (activeRequests > 0) {
        activeRequests--;
      }
      notifyAll();
      return state();
    }

    private void updateMaxConcurrentRequests(int newMaxConcurrentRequests) {
      if (newMaxConcurrentRequests != maxConcurrentRequests) {
        maxConcurrentRequests = newMaxConcurrentRequests;
        notifyAll();
      }
    }

    private State state() {
      return new State(activeRequests, maxConcurrentRequests);
    }

    private record State(int activeRequests, int maxConcurrentRequests) {}
  }
}
