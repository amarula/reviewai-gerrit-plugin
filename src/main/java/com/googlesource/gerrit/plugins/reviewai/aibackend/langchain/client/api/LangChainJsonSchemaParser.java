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

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getArray;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getObject;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.getString;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

final class LangChainJsonSchemaParser {

  private LangChainJsonSchemaParser() {}

  static JsonSchemaElement parse(JsonObject schemaObject) {
    if (schemaObject == null) {
      throw new IllegalArgumentException("Schema definition must be a JSON object");
    }
    return buildJsonSchemaElement(schemaObject);
  }

  static JsonObjectSchema parseObjectSchema(JsonObject schemaObject) {
    JsonSchemaElement element = parse(schemaObject);
    if (!(element instanceof JsonObjectSchema objectSchema)) {
      throw new IllegalArgumentException("Schema definition is not a JSON object");
    }
    return objectSchema;
  }

  private static JsonSchemaElement buildJsonSchemaElement(JsonObject schemaObject) {
    String type = getString(schemaObject, "type");
    if (type == null) {
      if (schemaObject.has("enum")) {
        return buildEnumSchema(schemaObject);
      }
      throw new IllegalArgumentException("Schema definition missing type: " + schemaObject);
    }

    return switch (type) {
      case "object" -> buildObjectSchema(schemaObject);
      case "array" -> buildArraySchema(schemaObject);
      case "string" ->
          schemaObject.has("enum") ? buildEnumSchema(schemaObject) : buildStringSchema(schemaObject);
      case "integer" -> buildIntegerSchema(schemaObject);
      case "number" -> buildNumberSchema(schemaObject);
      case "boolean" -> buildBooleanSchema(schemaObject);
      default -> throw new IllegalArgumentException("Unsupported schema type: " + type);
    };
  }

  private static JsonObjectSchema buildObjectSchema(JsonObject schemaObject) {
    JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
    setDescription(schemaObject, builder::description);

    JsonObject properties = getObject(schemaObject, "properties");
    if (properties != null) {
      for (var entry : properties.entrySet()) {
        JsonObject propertySchema = asObject(entry.getValue());
        if (propertySchema != null) {
          builder.addProperty(entry.getKey(), buildJsonSchemaElement(propertySchema));
        }
      }
    }

    JsonArray required = getArray(schemaObject, "required");
    if (required != null) {
      builder.required(toStringList(required));
    }

    JsonElement additionalProperties = schemaObject.get("additionalProperties");
    if (additionalProperties != null
        && additionalProperties.isJsonPrimitive()
        && additionalProperties.getAsJsonPrimitive().isBoolean()) {
      builder.additionalProperties(additionalProperties.getAsBoolean());
    }

    return builder.build();
  }

  private static JsonArraySchema buildArraySchema(JsonObject schemaObject) {
    JsonArraySchema.Builder builder = JsonArraySchema.builder();
    setDescription(schemaObject, builder::description);

    JsonObject items = asObject(schemaObject.get("items"));
    if (items != null) {
      builder.items(buildJsonSchemaElement(items));
    }

    return builder.build();
  }

  private static JsonStringSchema buildStringSchema(JsonObject schemaObject) {
    JsonStringSchema.Builder builder = JsonStringSchema.builder();
    setDescription(schemaObject, builder::description);
    return builder.build();
  }

  private static JsonIntegerSchema buildIntegerSchema(JsonObject schemaObject) {
    JsonIntegerSchema.Builder builder = JsonIntegerSchema.builder();
    setDescription(schemaObject, builder::description);
    return builder.build();
  }

  private static JsonNumberSchema buildNumberSchema(JsonObject schemaObject) {
    JsonNumberSchema.Builder builder = JsonNumberSchema.builder();
    setDescription(schemaObject, builder::description);
    return builder.build();
  }

  private static JsonBooleanSchema buildBooleanSchema(JsonObject schemaObject) {
    JsonBooleanSchema.Builder builder = JsonBooleanSchema.builder();
    setDescription(schemaObject, builder::description);
    return builder.build();
  }

  private static JsonEnumSchema buildEnumSchema(JsonObject schemaObject) {
    JsonEnumSchema.Builder builder = JsonEnumSchema.builder();
    setDescription(schemaObject, builder::description);
    JsonArray enumValues = getArray(schemaObject, "enum");
    if (enumValues != null) {
      builder.enumValues(toStringList(enumValues));
    }
    return builder.build();
  }

  private static void setDescription(JsonObject schemaObject, Consumer<String> descriptionConsumer) {
    String description = getString(schemaObject, "description");
    if (description != null) {
      descriptionConsumer.accept(description);
    }
  }

  private static JsonObject asObject(JsonElement element) {
    if (element != null && element.isJsonObject()) {
      return element.getAsJsonObject();
    }
    return null;
  }

  private static List<String> toStringList(JsonArray array) {
    List<String> values = new ArrayList<>();
    for (JsonElement element : array) {
      if (element != null && element.isJsonPrimitive()) {
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isString()) {
          values.add(primitive.getAsString());
        } else if (primitive.isNumber()) {
          values.add(primitive.getAsNumber().toString());
        } else if (primitive.isBoolean()) {
          values.add(Boolean.toString(primitive.getAsBoolean()));
        }
      }
    }
    return values;
  }
}
