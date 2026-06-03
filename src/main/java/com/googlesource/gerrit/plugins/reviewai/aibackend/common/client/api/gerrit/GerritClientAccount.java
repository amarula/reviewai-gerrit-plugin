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

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class GerritClientAccount extends GerritClientBase {
  public GerritClientAccount(Configuration config) {
    super(config);
    log.debug("GerritClientAccount initialized.");
  }

  public boolean isDisabledUser(String authorUsername) {
    log.debug(
        "User '{}' is not disabled; user and group filters are not configured.", authorUsername);
    return false;
  }

  public boolean isDisabledTopic(String topic) {
    List<String> enabledTopicFilter = config.getEnabledTopicFilter();
    List<String> disabledTopicFilter = config.getDisabledTopicFilter();
    boolean isDisabled =
        !enabledTopicFilter.contains(Configuration.ENABLED_TOPICS_ALL)
                && enabledTopicFilter.stream().noneMatch(topic::contains)
            || !topic.isEmpty() && disabledTopicFilter.stream().anyMatch(topic::contains);
    log.debug("Checking if topic '{}' is disabled: {}", topic, isDisabled);
    return isDisabled;
  }
}
