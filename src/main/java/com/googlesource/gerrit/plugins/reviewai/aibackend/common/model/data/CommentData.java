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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.HashMap;
import java.util.List;

@AllArgsConstructor
@Data
public class CommentData {
  private List<GerritComment> commentProperties;
  private List<GerritComment> addressedComments;
  private HashMap<String, GerritComment> commentMap;
  private HashMap<String, GerritComment> patchSetCommentMap;

  public CommentData(
      List<GerritComment> commentProperties,
      HashMap<String, GerritComment> commentMap,
      HashMap<String, GerritComment> patchSetCommentMap) {
    this(commentProperties, commentProperties, commentMap, patchSetCommentMap);
  }
}
