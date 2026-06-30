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

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class StringUtils {
  public static String backslashEachChar(String body) {
    StringBuilder slashedBody = new StringBuilder();

    for (char ch : body.toCharArray()) {
      slashedBody.append("\\\\").append(ch);
    }
    return slashedBody.toString();
  }

  public static String backslashDoubleQuotes(String body) {
    return body.replace("\"", "\\\"");
  }

  public static String backslashBackslashesAndDoubleQuotes(String body) {
    return body.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  public static String doubleBackslashDoubleQuotes(String body) {
    return body.replace("\"", "\\\\\"");
  }

  public static String deSlash(String target, String deSlashChars) {
    return target.replaceAll("\\\\([" + deSlashChars + "])", "$1");
  }

  public static String concatenate(List<String> components) {
    return String.join("", components);
  }

  public static String capitalizeFirstLetter(String str) {
    if (str == null || str.isEmpty()) {
      return str;
    }
    return str.substring(0, 1).toUpperCase() + str.substring(1);
  }

  public static String convertPascalCaseToWords(String pascalCase) {
    if (pascalCase == null || pascalCase.isEmpty()) {
      return pascalCase;
    }
    return pascalCase.replaceAll("(?<!^)([A-Z])", " $1");
  }

  public static String convertCamelToSnakeCase(String camelCase) {
    if (camelCase == null || camelCase.isEmpty()) {
      return camelCase;
    }
    return camelCase
        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
        .replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
        .toLowerCase();
  }

  public static String convertSnakeToPascalCase(String snakeCase) {
    if (snakeCase == null || snakeCase.isEmpty()) {
      return snakeCase;
    }
    snakeCase = snakeCase.toLowerCase();
    Pattern pattern = Pattern.compile("(?:^|_)(.)");
    Matcher matcher = pattern.matcher(snakeCase);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(result, matcher.group(1).toUpperCase());
    }
    matcher.appendTail(result);

    return result.toString();
  }

  public static String cutString(String str, int length) {
    if (str == null) {
      return "";
    }
    return str.length() <= length ? str : str.substring(0, length) + "...";
  }
}
