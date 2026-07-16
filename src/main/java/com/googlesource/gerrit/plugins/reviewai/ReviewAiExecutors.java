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

import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.gerrit.server.git.WorkQueue;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class ReviewAiExecutors {
  private static final String KEY_AGENT_EXECUTOR_MAXIMUM_POOL_SIZE = "agentExecutorMaximumPoolSize";
  private static final int DEFAULT_AGENT_EXECUTOR_MAXIMUM_POOL_SIZE = 4;

  private final ScheduledExecutorService agentExecutor;

  @Inject
  ReviewAiExecutors(
      WorkQueue workQueue, @PluginName String pluginName, PluginConfigFactory pluginConfigFactory) {
    int maximumPoolSize =
        pluginConfigFactory
            .getFromGerritConfig(pluginName)
            .getInt(
                KEY_AGENT_EXECUTOR_MAXIMUM_POOL_SIZE, DEFAULT_AGENT_EXECUTOR_MAXIMUM_POOL_SIZE);
    this.agentExecutor = workQueue.createQueue(maximumPoolSize, "ReviewAI agent executor");
  }

  public Executor getAgentExecutor() {
    return agentExecutor;
  }

  public void shutdown() {
    agentExecutor.shutdownNow();
    try {
      if (!agentExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
        log.warn("ReviewAI agent executor did not terminate within timeout");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while shutting down ReviewAI agent executor", e);
    }
  }
}
