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

package com.googlesource.gerrit.plugins.reviewai.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CollectionUtils {

  public static ArrayList<String> arrayToList(String[] input) {
    if (input == null || input.length == 0) {
      return new ArrayList<>();
    }
    return new ArrayList<>(Arrays.asList(input));
  }

  public static void putAllMergeStringLists(Map<String, Object> obj1, Map<String, Object> obj2) {
    obj2.forEach(
        (key, value) -> {
          if (value instanceof String) {
            obj1.put(key, value);
          } else if (value instanceof List) {
            obj1.merge(
                key,
                value,
                (oldValue, newValue) -> {
                  List<String> newList = new ArrayList<>();
                  addAllStringsSafely(newList, oldValue);
                  addAllStringsSafely(newList, newValue);
                  return newList;
                });
          }
        });
  }

  private static void addAllStringsSafely(List<String> targetList, Object possibleList) {
    if (possibleList instanceof List) {
      ((List<?>) possibleList)
          .forEach(
              item -> {
                if (item instanceof String) {
                  targetList.add((String) item);
                } else {
                  throw new IllegalArgumentException("List contains non-string item: " + item);
                }
              });
    }
  }
}
