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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.ClientBase;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class OpenAiParameters extends ClientBase {
  private final boolean isCommentEvent;

  public OpenAiParameters(Configuration config, boolean isCommentEvent) {
    super(config);
    this.isCommentEvent = isCommentEvent;
    log.debug("OpenAiParameters initialized with isCommentEvent: {}", isCommentEvent);
  }

  public double getAiTemperature() {
    log.debug("Getting AI temperature");
    if (isCommentEvent) {
      return retrieveTemperature(
          Configuration.KEY_AI_COMMENT_TEMPERATURE, Configuration.DEFAULT_AI_COMMENT_TEMPERATURE);
    } else {
      return retrieveTemperature(
          Configuration.KEY_AI_REVIEW_TEMPERATURE, Configuration.DEFAULT_AI_REVIEW_TEMPERATURE);
    }
  }

  public boolean isMultiAgentModeEnabled() {
    return config.getAiReviewCommitMessages() && config.getMultiAgentMode();
  }

  private Double retrieveTemperature(String temperatureKey, Double defaultTemperature) {
    return Double.parseDouble(config.getString(temperatureKey, String.valueOf(defaultTemperature)));
  }
}
