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

import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class ReviewAiLifecycle implements LifecycleListener {
  private final ReviewAiExecutors reviewAiExecutors;

  @Inject
  ReviewAiLifecycle(ReviewAiExecutors reviewAiExecutors) {
    this.reviewAiExecutors = reviewAiExecutors;
  }

  @Override
  public void start() {
    log.info("Starting ReviewAI lifecycle");
  }

  @Override
  public void stop() {
    log.info("Stopping ReviewAI lifecycle");
    reviewAiExecutors.shutdown();
    ReviewAiDb.stopManagedTcpServer();
  }
}
