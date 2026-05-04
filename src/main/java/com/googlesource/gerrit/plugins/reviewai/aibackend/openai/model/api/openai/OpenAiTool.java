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

package com.googlesource.gerrit.plugins.reviewai.aibackend.openai.model.api.openai;

import com.google.gson.annotations.SerializedName;
import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
@Data
public class OpenAiTool {
  @NonNull private String type;
  private String name;
  private String description;
  private Boolean strict;
  private Parameters parameters;

  @Data
  public static class Parameters {
    private String type;
    private Object properties;
    private List<String> required;

    @Data
    public static class Properties {
      private Property replies;
      // Field `changeId` expected in the response to correspond with the PatchSet changeId in the
      // request
      private Field changeId;

      @Data
      public static class Property {
        private String type;
        private Item items;

        @Data
        public static class Item {
          private String type;
          private ObjectProperties properties;
          private List<String> required;

          @Data
          public static class ObjectProperties {
            private Field id;
            private Field reply;
            private Field score;
            private Field relevance;
            private Field repeated;
            private Field conflicting;
            private Field filename;
            private Field lineNumber;
            private Field codeSnippet;
            private Field requestType;
            private Field otherDescription;
            private Field entityCategory;
            private Field contextRequiredEntity;
          }
        }
      }

      @Data
      public static class Field {
        private String type;
        private String description;

        @SerializedName("enum")
        private List<String> enumeration;
      }
    }
  }
}
