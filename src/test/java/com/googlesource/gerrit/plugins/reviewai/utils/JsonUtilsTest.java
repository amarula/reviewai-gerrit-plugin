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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonObject;
import org.junit.Test;

public class JsonUtilsTest {
  @Test
  public void parsesJsonObjectEmbeddedInText() {
    JsonObject object =
        JsonUtils.parseJsonObjectFromText(
            "dev.langchain4j.exception.HttpException: "
                + "{\"error\":{\"message\":\"model not found\"}}");

    assertEquals(
        "model not found",
        JsonUtils.getNonBlankString(JsonUtils.getObject(object, "error"), "message"));
  }

  @Test
  public void returnsNullForBlankStringMember() {
    JsonObject object = new JsonObject();
    object.addProperty("message", " ");

    assertNull(JsonUtils.getNonBlankString(object, "message"));
  }

  @Test
  public void returnsNullWhenTextDoesNotContainJsonObject() {
    assertNull(
        JsonUtils.parseJsonObjectFromText("java.net.SocketTimeoutException: Read timed out"));
  }
}
