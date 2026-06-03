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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LogArgTest {
  private static final int CUT_AT = 512;
  private static final String TRUNCATION_SUFFIX = "...";

  @Test
  public void truncatedCutsStringFields() {
    String value = "a".repeat(CUT_AT + 1);

    String result = String.valueOf(LogArg.truncated(new SerializableValue(value)));

    assertEquals(
        "SerializableValue{value=\"" + value.substring(0, CUT_AT) + TRUNCATION_SUFFIX + "\"}",
        result);
  }

  @Test
  public void truncatedUsesObjectFieldsInsteadOfRawToString() {
    String value = "b".repeat(CUT_AT + 1);

    String result = String.valueOf(LogArg.truncated(new ObjectWithLongToString(value)));

    assertEquals(
        "ObjectWithLongToString{value=\""
            + value.substring(0, CUT_AT)
            + TRUNCATION_SUFFIX
            + "\"}",
        result);
  }

  // Fixture with a private field so the test exercises LogArg's reflective field rendering.
  private static class SerializableValue {
    private final String value;

    private SerializableValue(String value) {
      this.value = value;
    }
  }

  private static class ObjectWithLongToString {
    private final String value;

    private ObjectWithLongToString(String value) {
      this.value = value;
    }

    @Override
    public String toString() {
      return value;
    }
  }
}
