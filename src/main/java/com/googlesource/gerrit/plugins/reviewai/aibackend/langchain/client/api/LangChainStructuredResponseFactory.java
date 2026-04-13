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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
class LangChainStructuredResponseFactory {

  private final String schemaResourcePath;

  ResponseFormat loadStructuredResponseFormat() {
    try (InputStream inputStream =
        LangChainStructuredResponseFactory.class
            .getClassLoader()
            .getResourceAsStream(schemaResourcePath)) {
      if (inputStream == null) {
        log.warn(
            "Structured output schema resource {} not found; falling back to free-form text",
            schemaResourcePath);
        return null;
      }

      JsonObject root =
          JsonParser.parseReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
              .getAsJsonObject();
      JsonObject schemaDefinition = LangChainToolSchemaUtils.getStructuredOutputDefinition(root);
      String schemaName = LangChainToolSchemaUtils.getStructuredOutputName(root);
      if (schemaDefinition == null || schemaName == null) {
        log.warn(
            "Structured output schema resource {} missing schema definition; ignoring",
            schemaResourcePath);
        return null;
      }

      JsonSchemaElement rootElement = null;
      if (schemaDefinition.isJsonObject()) {
        try {
          rootElement = LangChainJsonSchemaParser.parse(schemaDefinition.getAsJsonObject());
        } catch (Exception e) {
          log.warn(
              "Failed to convert structured output schema {} into LangChain schema classes",
              schemaResourcePath,
              e);
        }
      }

      if (rootElement == null) {
        log.warn(
            "Structured output schema {} could not be converted; structured responses disabled",
            schemaResourcePath);
        return null;
      }

      JsonSchema jsonSchema =
          JsonSchema.builder().name(schemaName).rootElement(rootElement).build();

      ResponseFormat responseFormat =
          ResponseFormat.builder().type(ResponseFormatType.JSON).jsonSchema(jsonSchema).build();

      log.debug("Loaded structured output schema '{}' from {}", schemaName, schemaResourcePath);
      return responseFormat;
    } catch (Exception e) {
      log.warn(
          "Failed to load structured output schema from {}. Falling back to free-form responses",
          schemaResourcePath,
          e);
      return null;
    }
  }
}
