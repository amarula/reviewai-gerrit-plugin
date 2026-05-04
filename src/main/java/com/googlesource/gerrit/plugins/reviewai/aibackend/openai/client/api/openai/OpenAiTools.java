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

import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai.OpenAiTool;
import com.googlesource.gerrit.plugins.reviewai.utils.FileUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStreamReader;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.jsonToClass;

@Slf4j
public class OpenAiTools {
  public enum Functions {
    getContent,
    grep,
    tree
  }

  private static final String FILENAME_TOOL_FORMAT = "config/%sTool.json";

  private final String functionName;

  public OpenAiTools(Functions function) {
    functionName = function.name();
  }

  public OpenAiTool retrieveFunctionTool() {
    OpenAiTool tools;
    try (InputStreamReader reader =
        FileUtils.getInputStreamReader(String.format(FILENAME_TOOL_FORMAT, functionName))) {
      tools = jsonToClass(reader, OpenAiTool.class);
      if (tools.getStrict() == null) {
        tools.setStrict(false);
      }
      log.debug("Successfully loaded OpenAI tool {} from JSON.", functionName);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load data for OpenAI `" + functionName + "` tool", e);
    }
    return tools;
  }
}
