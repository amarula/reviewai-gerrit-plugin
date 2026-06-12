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

package com.googlesource.gerrit.plugins.reviewai.web.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class AiReviewHistoryInfo {
  private final List<Entry> entries;

  @Getter
  @AllArgsConstructor
  public static class Entry {
    private final String id;
    private final String changeMessageId;
    private final String role;
    private final boolean systemMessage;
    private final String author;
    private final String updated;
    private final Integer patchSet;
    private final String filename;
    private final Integer line;
    private final String reviewScore;
    private final String message;
  }
}
