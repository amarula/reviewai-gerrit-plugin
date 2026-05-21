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

package com.googlesource.gerrit.plugins.reviewai.utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static com.googlesource.gerrit.plugins.reviewai.utils.RegexUtils.patternJoinAlternation;

@Slf4j
public class JsonUtils extends TextUtils {
  private static final String INDENT = "    ";
  private static final Pattern JSON_DELIMITED =
      Pattern.compile(
          "^.*?" + CODE_DELIMITER + "json\\s*(.*)\\s*" + CODE_DELIMITER + ".*$", Pattern.DOTALL);
  private static final Pattern JSON_ARRAY = Pattern.compile("^\\[.*\\]$", Pattern.DOTALL);
  private static final Pattern JSON_OBJECT = Pattern.compile("^\\{.*\\}$", Pattern.DOTALL);
  private static final Pattern JSON_COMPLEX_VALUE =
      Pattern.compile(patternJoinAlternation(JSON_ARRAY, JSON_OBJECT), Pattern.DOTALL);

  public static String unwrapJsonCode(String text) {
    return JSON_DELIMITED.matcher(text).replaceAll("$1");
  }

  public static List<String> jsonArrayToList(String input) {
    log.debug("Starting to convert Json Array to list: `{}`", input);
    if (input == null || input.isEmpty() || !JSON_ARRAY.matcher(input).matches()) {
      return new ArrayList<>();
    }
    log.debug("Potential Json Array found");
    JsonElement jsonArray = parseJsonWithDeSlash(input);
    if (jsonArray == null || jsonArray.isJsonNull()) {
      return new ArrayList<>();
    }
    log.debug("Converting Json Array to list: {}", jsonArray);
    return StreamSupport.stream(jsonArray.getAsJsonArray().spliterator(), false)
        .map(JsonElement::getAsString)
        .collect(Collectors.toList());
  }

  public static boolean isJsonObjectAsString(String text) {
    return JSON_OBJECT.matcher(text).matches() || JSON_DELIMITED.matcher(text).matches();
  }

  public static String prettyStringifyMap(Map<String, String> map) {
    log.info("Starting to pretty stringify map: {}", map);
    return joinWithNewLine(
        map.entrySet().stream().map(JsonUtils::formatEntry).collect(Collectors.toList()));
  }

  public static String prettyFormatList(List<String> list) {
    return formatJsonArray(getGson().toJsonTree(list).getAsJsonArray(), "    ");
  }

  public static JsonObject getOrCreateObject(JsonObject parent, String key) {
    if (parent.has(key) && parent.get(key).isJsonObject()) {
      return parent.getAsJsonObject(key);
    }
    JsonObject child = new JsonObject();
    parent.add(key, child);
    return child;
  }

  public static Long getLong(JsonObject object, String memberName) {
    JsonElement element = getElement(object, memberName);
    if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
      return null;
    }
    return element.getAsLong();
  }

  public static String getString(JsonObject object, String memberName) {
    JsonElement element = getElement(object, memberName);
    if (element == null || !element.isJsonPrimitive()) {
      return null;
    }
    return element.getAsJsonPrimitive().getAsString();
  }

  public static JsonObject getObject(JsonObject object, String memberName) {
    JsonElement element = getElement(object, memberName);
    if (element == null || !element.isJsonObject()) {
      return null;
    }
    return element.getAsJsonObject();
  }

  public static JsonArray getArray(JsonObject object, String memberName) {
    JsonElement element = getElement(object, memberName);
    if (element == null || !element.isJsonArray()) {
      return null;
    }
    return element.getAsJsonArray();
  }

  private static JsonElement getElement(JsonObject object, String memberName) {
    if (object == null || !object.has(memberName)) {
      return null;
    }
    JsonElement element = object.get(memberName);
    return element == null || element.isJsonNull() ? null : element;
  }

  private static String formatEntry(Map.Entry<String, String> entry) {
    String key = entry.getKey();
    String value = entry.getValue();
    log.debug("Processing entry: key={}, value=`{}`", key, value);

    return formatValue(key, value, "");
  }

  private static String formatValue(String key, String value, String indent) {
    JsonElement jsonElement = parseJsonWithDeSlash(value);
    if (jsonElement != null) {
      StringBuilder jsonString = new StringBuilder(indent + key + COLON_SPACE);
      if (jsonElement.isJsonObject()) {
        log.debug("Value is a valid JSON object; formatting JSON for key={}", key);
        jsonString.append(formatJsonObject(jsonElement.getAsJsonObject(), indent + INDENT));
      } else if (jsonElement.isJsonArray()) {
        log.debug("Value is a valid JSON array; formatting JSON array for key={}", key);
        jsonString.append(formatJsonArray(jsonElement.getAsJsonArray(), indent + INDENT));
      } else if (jsonElement.isJsonPrimitive()) {
        log.debug("Value for key {} is not JSON element: `{}`", key, value);
        jsonString.append(indent).append(jsonElement.getAsString());
      }
      return jsonString.toString();
    }
    return key + COLON_SPACE + value;
  }

  private static JsonElement parseJsonWithDeSlash(String str) {
    try {
      JsonElement element = JsonParser.parseString(str);
      if (!element.isJsonObject()
          && !element.isJsonArray()
          && JSON_COMPLEX_VALUE.matcher(str).matches()) {
        element = JsonParser.parseString(deSlashQuotes(str));
        log.debug("Detected potential Json element as String in `{}`", element);
      }
      return element;
    } catch (JsonSyntaxException e) {
      log.debug("String is not a valid JSON: {}", str);
      return null;
    }
  }

  private static String formatJsonObject(JsonObject jsonObject, String indent) {
    log.debug("Formatting JsonObject: {}", jsonObject);
    if (jsonObject.size() == 0) {
      return "";
    }
    return "\n"
        + joinWithNewLine(
            jsonObject.entrySet().stream()
                .map(
                    entry -> {
                      JsonElement subValue = entry.getValue();
                      StringBuilder objBuilder =
                          new StringBuilder(indent + entry.getKey() + COLON_SPACE);
                      if (subValue.isJsonObject()) {
                        log.debug("Key={} contains a nested JsonObject", entry.getKey());
                        objBuilder.append(
                            formatJsonObject(subValue.getAsJsonObject(), indent + INDENT));
                      } else if (subValue.isJsonArray()) {
                        log.debug("Key={} contains a nested JsonArray", entry.getKey());
                        objBuilder.append(
                            formatJsonArray(subValue.getAsJsonArray(), indent + INDENT));
                      } else if (JSON_COMPLEX_VALUE.matcher(subValue.getAsString()).matches()) {
                        log.debug(
                            "Key={} contains a potential Json element as String", entry.getKey());
                        return formatValue(entry.getKey(), subValue.getAsString(), indent);
                      } else {
                        log.debug("Key={} contains a String", entry.getKey());
                        objBuilder.append(subValue.getAsString());
                      }
                      return objBuilder.toString();
                    })
                .collect(Collectors.toList()));
  }

  private static String formatJsonArray(JsonArray jsonArray, String indent) {
    log.debug("Formatting JsonArray: {}", jsonArray);
    if (jsonArray.isEmpty()) {
      return "";
    }
    return "\n"
        + joinWithNewLine(
            StreamSupport.stream(jsonArray.spliterator(), false)
                .map(
                    element -> {
                      if (element.isJsonObject()) {
                        log.debug(
                            "Element=`{}` contains a nested JsonObject", element.getAsString());
                        return formatJsonObject(element.getAsJsonObject(), indent + INDENT);
                      } else if (element.isJsonArray()) {
                        log.debug(
                            "Element=`{}` contains a nested JsonArray", element.getAsString());
                        return formatJsonArray(element.getAsJsonArray(), indent + INDENT);
                      } else if (JSON_COMPLEX_VALUE.matcher(element.getAsString()).matches()) {
                        log.debug(
                            "Element`=`{}` contains a potential Json element as String",
                            element.getAsString());
                        return formatValue("", element.getAsString(), indent);
                      } else {
                        return indent + element.getAsString();
                      }
                    })
                .collect(Collectors.toList()));
  }
}
