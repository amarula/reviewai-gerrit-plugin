/*
 * Copyright (c) 2026. Amarula Solutions
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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.messages;

import dev.langchain4j.data.message.ChatMessage;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

@Slf4j
public final class LangChainMessageTextExtractor {

  private static final String[] CANDIDATE_METHODS = {"text", "content", "getText", "singleText"};

  private LangChainMessageTextExtractor() {}

  public static String extractText(ChatMessage message) {
    if (message == null) {
      return "";
    }
    for (String methodName : CANDIDATE_METHODS) {
      try {
        Method method = message.getClass().getMethod(methodName);
        if (method.getReturnType().equals(String.class)
            && Modifier.isPublic(method.getModifiers())) {
          Object value = method.invoke(message);
          if (value != null) {
            return (String) value;
          }
        }
      } catch (NoSuchMethodException ignored) {
        // Method not present on this message; continue trying others.
      } catch (Exception e) {
        log.debug(
            "Failed to invoke method {} on {} while extracting text: {}",
            methodName,
            message.getClass().getName(),
            e.getMessage());
      }
    }

    String fallback = message.toString();
    return fallback == null ? "" : fallback;
  }
}
