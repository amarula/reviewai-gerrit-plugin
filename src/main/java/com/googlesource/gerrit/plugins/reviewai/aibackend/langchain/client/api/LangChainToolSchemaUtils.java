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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

final class LangChainToolSchemaUtils {
  private LangChainToolSchemaUtils() {}

  static JsonObject getFunctionDefinition(JsonObject root) {
    JsonObject nestedFunction = root.getAsJsonObject("function");
    if (nestedFunction != null) {
      return nestedFunction;
    }
    JsonElement typeElement = root.get("type");
    if (typeElement != null
        && typeElement.isJsonPrimitive()
        && "function".equals(typeElement.getAsString())) {
      return root;
    }
    return null;
  }

  static JsonObject getStructuredOutputDefinition(JsonObject root) {
    JsonObject directSchema = root.getAsJsonObject("schema");
    if (directSchema != null) {
      return directSchema;
    }

    JsonObject functionDefinition = getFunctionDefinition(root);
    if (functionDefinition != null) {
      return functionDefinition.getAsJsonObject("parameters");
    }
    return null;
  }

  static String getStructuredOutputName(JsonObject root) {
    JsonElement directName = root.get("name");
    if (directName != null && directName.isJsonPrimitive()) {
      return directName.getAsString();
    }

    JsonObject functionDefinition = getFunctionDefinition(root);
    if (functionDefinition == null) {
      return null;
    }
    JsonElement functionName = functionDefinition.get("name");
    if (functionName != null && functionName.isJsonPrimitive()) {
      return functionName.getAsString();
    }
    return null;
  }
}
