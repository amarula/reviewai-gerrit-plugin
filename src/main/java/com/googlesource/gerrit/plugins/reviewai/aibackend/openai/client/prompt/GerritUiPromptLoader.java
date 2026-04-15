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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.prompt;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

@Slf4j
final class GerritUiPromptLoader {
  public static final String PROMPTS_URL_PROPERTY = "reviewai.gerritUiPromptUrl";

  private static final String DEFAULT_PROMPTS_URL =
      "https://gerrit.googlesource.com/gerrit/+/HEAD/polygerrit-ui/app/elements/change/"
          + "gr-ai-prompt-dialog/prompts.ts?format=TEXT";
  private static final String HELP_ME_REVIEW_PROMPT = "HELP_ME_REVIEW_PROMPT";
  private static final HttpClient HTTP_CLIENT =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  private GerritUiPromptLoader() {}

  static String resolveReviewInstructions(String fallbackPrompt) {
    try {
      String source = fetchPromptsSource();
      String prompt = extractTemplateLiteral(source, HELP_ME_REVIEW_PROMPT);
      String normalizedPrompt = normalizeReviewPrompt(prompt);
      if (!normalizedPrompt.isBlank()) {
        return normalizedPrompt;
      }
    } catch (IOException e) {
      log.warn("Unable to fetch Gerrit UI review prompt: {}", e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.warn("Interrupted while fetching Gerrit UI review prompt");
    } catch (IllegalArgumentException e) {
      log.warn("Unable to parse Gerrit UI review prompt: {}", e.getMessage());
    }
    return fallbackPrompt;
  }

  private static String fetchPromptsSource() throws IOException, InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(getPromptsUrl()))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) {
      throw new IOException("Unexpected Gerrit prompts response: HTTP " + response.statusCode());
    }

    byte[] decodedContent = Base64.getDecoder().decode(response.body().trim());
    return new String(decodedContent, StandardCharsets.UTF_8);
  }

  private static String getPromptsUrl() {
    return System.getProperty(PROMPTS_URL_PROPERTY, DEFAULT_PROMPTS_URL);
  }

  private static String normalizeReviewPrompt(String prompt) {
    String normalizedPrompt = prompt.replace("\r\n", "\n").strip();
    int patchSectionStart = normalizedPrompt.indexOf("\nPatch:");
    if (patchSectionStart >= 0) {
      normalizedPrompt = normalizedPrompt.substring(0, patchSectionStart).strip();
    }
    return normalizedPrompt;
  }

  private static String extractTemplateLiteral(String source, String constantName) {
    String constantDeclaration = "export const " + constantName;
    int declarationStart = source.indexOf(constantDeclaration);
    if (declarationStart < 0) {
      throw new IllegalArgumentException("Constant not found: " + constantName);
    }

    int templateStart = source.indexOf('`', declarationStart);
    if (templateStart < 0) {
      throw new IllegalArgumentException("Template literal not found for: " + constantName);
    }

    StringBuilder value = new StringBuilder();
    boolean escaped = false;
    for (int i = templateStart + 1; i < source.length(); i++) {
      char ch = source.charAt(i);
      if (escaped) {
        switch (ch) {
          case '`':
          case '\\':
          case '$':
            value.append(ch);
            break;
          case 'n':
            value.append('\n');
            break;
          case 'r':
            value.append('\r');
            break;
          case 't':
            value.append('\t');
            break;
          case '\n':
            break;
          default:
            value.append('\\').append(ch);
            break;
        }
        escaped = false;
        continue;
      }

      if (ch == '\\') {
        escaped = true;
        continue;
      }
      if (ch == '`') {
        return value.toString().strip();
      }
      value.append(ch);
    }

    throw new IllegalArgumentException("Unterminated template literal for: " + constantName);
  }
}
