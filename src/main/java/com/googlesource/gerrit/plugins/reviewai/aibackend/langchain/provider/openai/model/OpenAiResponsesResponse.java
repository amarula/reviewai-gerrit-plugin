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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.provider.openai.model;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@ToString(callSuper = true)
public class OpenAiResponsesResponse extends OpenAiResponse {
  @SerializedName("output_text")
  private String outputText;

  private List<OutputItem> output;

  @Data
  public static class OutputItem {
    private String id;
    private String type;
    private String role;
    private String name;
    private String arguments;

    @SerializedName("call_id")
    private String callId;

    private List<Content> content;

    @Data
    public static class Content {
      private String type;
      private String text;
    }
  }
}
