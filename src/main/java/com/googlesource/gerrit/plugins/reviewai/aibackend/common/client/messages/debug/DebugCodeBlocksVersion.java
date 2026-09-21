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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.prettyStringifyMap;

import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.utils.PluginBuild;
import java.util.List;

public class DebugCodeBlocksVersion extends DebugCodeBlocksComposer {
  public DebugCodeBlocksVersion(Localizer localizer) {
    super(localizer, "message.dump.version.title");
  }

  public String getDebugCodeBlock() {
    return super.getDebugCodeBlock(List.of(prettyStringifyMap(PluginBuild.getVersionInfo())));
  }
}
