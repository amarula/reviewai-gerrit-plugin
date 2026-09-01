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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

final class SpecializedReviewStageExecutor {
  @FunctionalInterface
  interface StageCall<T> {
    T run() throws Exception;
  }

  private final Executor executor;

  SpecializedReviewStageExecutor(Executor executor) {
    this.executor = executor;
  }

  <T> CompletableFuture<T> supplyAsync(
      ChangeSetData changeSetData, StageCall<T> stageCall) {
    return changeSetData.getAiRequestCancellation().supplyAsync(stageCall::run, executor);
  }

  <T> T join(CompletableFuture<T> future) throws Exception {
    try {
      return future.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof Exception exception) {
        throw exception;
      }
      throw e;
    }
  }
}
