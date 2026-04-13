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

import com.openai.client.OpenAIClient;
import com.openai.core.http.HttpResponseFor;
import com.openai.models.responses.Response;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.AiConnectionFailException;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiResponse;
import com.googlesource.gerrit.plugins.reviewai.utils.TimeUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.jsonToClass;
import static com.googlesource.gerrit.plugins.reviewai.utils.ThreadUtils.threadSleep;

@Slf4j
public class OpenAiPoller {
  public static final String COMPLETED_STATUS = "completed";
  public static final String CANCELLED_STATUS = "cancelled";
  public static final String FAILED_STATUS = "failed";
  public static final String REQUIRES_ACTION_STATUS = "requires_action";

  private static final Set<String> PENDING_STATUSES =
      new HashSet<>(Arrays.asList("queued", "in_progress", "cancelling"));

  private final int pollingTimeout;
  private final int pollingInterval;
  private final Configuration config;

  @Getter private int pollingCount;
  @Getter private double elapsedTime;

  public OpenAiPoller(Configuration config) {
    this.config = config;
    pollingTimeout = config.getAiPollingTimeout();
    pollingInterval = config.getAiPollingInterval();
    elapsedTime = 0.0;
    pollingCount = 0;
  }

  public <T extends OpenAiResponse> T runPoll(
      OpenAIClient client, T pollResponse, Class<T> clazz)
      throws AiConnectionFailException {
    long startTime = TimeUtils.getCurrentMillis();

    while (isPending(pollResponse.getStatus())) {
      pollingCount++;
      log.debug("Polling request #{}", pollingCount);
      threadSleep(pollingInterval);
      try (HttpResponseFor<Response> response =
          client.responses().withRawResponse().retrieve(pollResponse.getId())) {
        String responseBody = OpenAiSdkClientFactory.readBody(response);
        log.debug("OpenAI Poll response: {}", responseBody);
        pollResponse = jsonToClass(responseBody, clazz);
      } catch (Exception e) {
        throw new AiConnectionFailException(
            String.format(
                "OpenAI response polling failed for response `%s` against `%s`: %s",
                pollResponse.getId(),
                OpenAiSdkClientFactory.getResolvedBaseUrl(config),
                OpenAiSdkClientFactory.describeException(e)),
            e);
      }
      elapsedTime = (double) (TimeUtils.getCurrentMillis() - startTime) / 1000;
      if (elapsedTime >= pollingTimeout) {
        log.error("Polling timed out after {} seconds.", elapsedTime);
        throw new AiConnectionFailException(
            String.format(
                "OpenAI response polling timed out after %.3f seconds for response `%s` against `%s`",
                elapsedTime, pollResponse.getId(), OpenAiSdkClientFactory.getResolvedBaseUrl(config)));
      }
    }
    return pollResponse;
  }

  public static boolean isNotCompleted(String status) {
    return status == null || !status.equals(COMPLETED_STATUS);
  }

  private static boolean isPending(String status) {
    return status == null || status.isEmpty() || PENDING_STATUSES.contains(status);
  }
}
