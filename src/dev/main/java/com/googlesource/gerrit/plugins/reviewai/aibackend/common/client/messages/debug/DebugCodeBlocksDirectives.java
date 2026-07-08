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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug;

import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;

import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.getNumberedList;

public class DebugCodeBlocksDirectives extends DebugCodeBlocksComposer {
  public DebugCodeBlocksDirectives(Localizer localizer) {
    super(localizer, "message.dump.directives.title");
  }

  public String getDebugCodeBlock(List<String> directives) {
    if (directives == null || directives.isEmpty()) {
      return localizer.getText("message.dump.directives.empty");
    }
    return super.getDebugCodeBlock(getNumberedList(directives));
  }
}
