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
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpResponse;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class OpenAiSdkClientFactory {
  private OpenAiSdkClientFactory() {}

  public static OpenAIClient create(Configuration config) {
    return OpenAIOkHttpClient.builder()
        .apiKey(config.getAiToken())
        .baseUrl(normalizeBaseUrl(config.getAiDomain()))
        .timeout(Duration.ofSeconds(config.getAiConnectionTimeout()))
        .maxRetries(config.getAiConnectionMaxRetryAttempts())
        .build();
  }

  public static String readBody(HttpResponse response) throws IOException {
    return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
  }

  private static String normalizeBaseUrl(String domain) {
    String normalized = domain.endsWith("/") ? domain.substring(0, domain.length() - 1) : domain;
    if (normalized.endsWith("/v1")) {
      return normalized;
    }
    return normalized + "/v1";
  }
}
