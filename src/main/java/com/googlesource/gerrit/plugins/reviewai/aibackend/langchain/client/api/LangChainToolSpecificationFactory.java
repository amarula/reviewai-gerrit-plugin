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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getString;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
class LangChainToolSpecificationFactory {

  private final String schemaResourcePath;

  ToolSpecification loadToolSpecification() {
    try (InputStream inputStream =
        LangChainToolSpecificationFactory.class
            .getClassLoader()
            .getResourceAsStream(schemaResourcePath)) {
      if (inputStream == null) {
        log.warn("Tool schema resource {} not found; skipping", schemaResourcePath);
        return null;
      }

      JsonObject root =
          JsonParser.parseReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
              .getAsJsonObject();
      JsonObject function = LangChainToolSchemaUtils.getFunctionDefinition(root);
      if (function == null) {
        log.warn(
            "Tool schema resource {} missing function definition; skipping", schemaResourcePath);
        return null;
      }

      String name = getString(function, "name");
      if (name == null || name.isBlank()) {
        log.warn("Tool schema resource {} missing function name; skipping", schemaResourcePath);
        return null;
      }

      JsonObject parametersObject = function.getAsJsonObject("parameters");
      if (parametersObject == null) {
        log.warn(
            "Tool schema resource {} missing function parameters definition; skipping",
            schemaResourcePath);
        return null;
      }

      JsonObjectSchema parametersSchema;
      try {
        parametersSchema = LangChainJsonSchemaParser.parseObjectSchema(parametersObject);
      } catch (Exception e) {
        log.warn(
            "Failed to convert tool schema {} into LangChain schema classes; skipping",
            schemaResourcePath,
            e);
        return null;
      }

      ToolSpecification.Builder builder =
          ToolSpecification.builder().name(name).parameters(parametersSchema);

      String description = getString(function, "description");
      if (description != null && !description.isBlank()) {
        builder.description(description);
      }

      ToolSpecification toolSpecification = builder.build();
      log.debug("Loaded tool specification '{}' from {}", name, schemaResourcePath);
      return toolSpecification;
    } catch (Exception e) {
      log.warn(
          "Failed to load tool specification from {}. Tool execution disabled for this schema",
          schemaResourcePath,
          e);
      return null;
    }
  }

}
