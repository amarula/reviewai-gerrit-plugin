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

import static com.googlesource.gerrit.plugins.reviewai.data.ReviewAgentRequestStatusStore.KEY_REQUEST_STATUSES;
import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.prettyStringifyMap;

import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class DebugCodeBlocksDataDump extends DebugCodeBlocksComposer {
  private final List<String> dataDump = new ArrayList<>();

  public DebugCodeBlocksDataDump(
      Localizer localizer, PluginDataHandlerProvider pluginDataHandlerProvider) {
    super(localizer, "message.dump.stored.data.title");
    retrieveStoredData(pluginDataHandlerProvider);
  }

  public String getDebugCodeBlock() {
    return super.getDebugCodeBlock(dataDump);
  }

  private void retrieveStoredData(PluginDataHandlerProvider pluginDataHandlerProvider) {
    addStoredData("GlobalScope", pluginDataHandlerProvider.getGlobalScope(), false);
    addStoredData("ProjectScope", pluginDataHandlerProvider.getProjectScope(), false);
    addStoredData("ChangeScope", pluginDataHandlerProvider.getChangeScope(), true);
  }

  private void addStoredData(
      String dataKey, PluginDataHandler dataHandler, boolean omitRequestStatuses) {
    log.debug("Populating data key {}", dataKey);
    dataDump.add(getAsTitle(dataKey));
    try {
      Map<String, String> values = new HashMap<>(dataHandler.getAllValues());
      if (omitRequestStatuses) {
        // Response bodies can contain earlier /show output, which would recursively reproduce
        // entire debug panels inside the Change Scope dump.
        values.remove(KEY_REQUEST_STATUSES);
      }
      dataDump.add(prettyStringifyMap(values) + "\n");
    } catch (Exception e) {
      log.warn("Exception while retrieving data", e);
    }
  }
}
