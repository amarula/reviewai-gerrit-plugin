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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.HttpResponse;
import com.openai.errors.OpenAIServiceException;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

public final class OpenAiSdkClientFactory {
  private OpenAiSdkClientFactory() {}

  public static OpenAIClient create(Configuration config) {
    return create(config, config.getAiConnectionMaxRetryAttempts());
  }

  static OpenAIClient createWithoutRetries(Configuration config) {
    return create(config, 0);
  }

  private static OpenAIClient create(Configuration config, int maxRetries) {
    return OpenAIOkHttpClient.builder()
        .apiKey(config.getAiToken())
        .baseUrl(getResolvedBaseUrl(config))
        .timeout(Duration.ofSeconds(config.getAiConnectionTimeout()))
        .maxRetries(maxRetries)
        .build();
  }

  public static boolean isTimeout(Throwable throwable) {
    Throwable cause = throwable;
    while (cause != null) {
      if (cause instanceof SocketTimeoutException
          || cause instanceof InterruptedIOException
          && cause.getMessage() != null
          && cause.getMessage().toLowerCase(Locale.ROOT).contains("timeout")) {
        return true;
      }
      cause = cause.getCause();
    }
    return false;
  }

  public static String getResolvedBaseUrl(Configuration config) {
    return normalizeBaseUrl(config.getAiDomain());
  }

  public static String readBody(HttpResponse response) throws IOException {
    return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
  }

  public static String describeException(Throwable throwable) {
    if (throwable == null) {
      return "no exception details available";
    }

    if (throwable instanceof OpenAIServiceException serviceException) {
      return String.format(
          "status=%s code=%s type=%s param=%s body=%s",
          serviceException.statusCode(),
          serviceException.code().orElse(""),
          serviceException.type().orElse(""),
          serviceException.param().orElse(""),
          serviceException.body());
    }

    String message = throwable.getMessage();
    if (message != null && !message.isBlank()) {
      return message;
    }

    return throwable.getClass().getSimpleName();
  }

  private static String normalizeBaseUrl(String domain) {
    String normalized = domain.endsWith("/") ? domain.substring(0, domain.length() - 1) : domain;
    if (normalized.endsWith("/v1")) {
      return normalized;
    }
    return normalized + "/v1";
  }
}
