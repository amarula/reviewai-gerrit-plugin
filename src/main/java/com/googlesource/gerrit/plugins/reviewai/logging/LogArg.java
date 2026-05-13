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

package com.googlesource.gerrit.plugins.reviewai.logging;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

public final class LogArg {
  private static final int MAX_FIELD_LENGTH = 512;
  private static final int MAX_DEPTH = 10;
  private static final int MAX_ITEMS = 20;

  private final Object value;
  private final int maxFieldLength;

  private LogArg(Object value, int maxFieldLength) {
    this.value = value;
    this.maxFieldLength = maxFieldLength;
  }

  public static Object truncated(Object value) {
    return new LogArg(value, MAX_FIELD_LENGTH);
  }

  @Override
  public String toString() {
    StringBuilder buffer = new StringBuilder();
    render(buffer, value, 0, new IdentityHashMap<>());
    return buffer.toString();
  }

  private void render(
      StringBuilder buffer,
      Object obj,
      int depth,
      IdentityHashMap<Object, Boolean> seen) {

    if (obj == null) {
      buffer.append("null");
      return;
    }

    if (depth > MAX_DEPTH) {
      buffer.append("...");
      return;
    }

    if (obj instanceof String s) {
      buffer.append('"').append(truncate(s)).append('"');
      return;
    }

    if (obj instanceof Number || obj instanceof Boolean || obj instanceof Character || obj instanceof Enum<?>) {
      buffer.append(obj);
      return;
    }

    Class<?> clazz = obj.getClass();

    if (clazz.isArray()) {
      renderArray(buffer, obj, depth, seen);
      return;
    }

    if (obj instanceof Collection<?> collection) {
      renderCollection(buffer, collection, depth, seen);
      return;
    }

    if (obj instanceof Map<?, ?> map) {
      renderMap(buffer, map, depth, seen);
      return;
    }

    if (seen.containsKey(obj)) {
      buffer.append("<cycle>");
      return;
    }

    seen.put(obj, Boolean.TRUE);
    renderObjectFields(buffer, obj, depth, seen);
    seen.remove(obj);
  }

  private void renderArray(
      StringBuilder buffer,
      Object array,
      int depth,
      IdentityHashMap<Object, Boolean> seen) {

    buffer.append("[");
    int length = Array.getLength(array);
    int limit = Math.min(length, MAX_ITEMS);

    for (int i = 0; i < limit; i++) {
      if (i > 0) {
        buffer.append(", ");
      }
      render(buffer, Array.get(array, i), depth + 1, seen);
    }

    if (length > limit) {
      buffer.append(", ... +").append(length - limit).append(" more");
    }

    buffer.append("]");
  }

  private void renderCollection(
      StringBuilder buffer,
      Collection<?> collection,
      int depth,
      IdentityHashMap<Object, Boolean> seen) {

    buffer.append("[");
    int i = 0;

    for (Object item : collection) {
      if (i >= MAX_ITEMS) {
        buffer.append(", ... +").append(collection.size() - MAX_ITEMS).append(" more");
        break;
      }

      if (i > 0) {
        buffer.append(", ");
      }

      render(buffer, item, depth + 1, seen);
      i++;
    }

    buffer.append("]");
  }

  private void renderMap(
      StringBuilder buffer,
      Map<?, ?> map,
      int depth,
      IdentityHashMap<Object, Boolean> seen) {

    buffer.append("{");
    int i = 0;

    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (i >= MAX_ITEMS) {
        buffer.append(", ... +").append(map.size() - MAX_ITEMS).append(" more");
        break;
      }

      if (i > 0) {
        buffer.append(", ");
      }

      render(buffer, entry.getKey(), depth + 1, seen);
      buffer.append("=");
      render(buffer, entry.getValue(), depth + 1, seen);

      i++;
    }

    buffer.append("}");
  }

  private void renderObjectFields(
      StringBuilder buffer,
      Object obj,
      int depth,
      IdentityHashMap<Object, Boolean> seen) {

    Class<?> clazz = obj.getClass();
    buffer.append(clazz.getSimpleName()).append("{");

    Field[] fields = clazz.getDeclaredFields();

    for (int i = 0; i < fields.length; i++) {
      Field field = fields[i];

      if (i > 0) {
        buffer.append(", ");
      }

      buffer.append(field.getName()).append("=");

      try {
        field.setAccessible(true);
        render(buffer, field.get(obj), depth + 1, seen);
      } catch (RuntimeException | IllegalAccessException e) {
        buffer.append("<unavailable>");
      }
    }

    buffer.append("}");
  }

  private String truncate(String value) {
    if (value.length() <= maxFieldLength) {
      return value;
    }

    return value.substring(0, maxFieldLength) + "...";
  }
}
