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

import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static com.googlesource.gerrit.plugins.reviewai.utils.JsonUtils.prettyStringifyMap;

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
    for (Method method : pluginDataHandlerProvider.getClass().getDeclaredMethods()) {
      method.setAccessible(true);
      try {
        String methodName = method.getName();
        log.debug("Retrieving stored method {}", methodName);
        if (!methodName.startsWith("get") || !methodName.endsWith("Scope")) continue;
        String dataKey = methodName.replaceAll("^get", "");
        log.debug("Populating data key {}", dataKey);
        dataDump.add(getAsTitle(dataKey));
        PluginDataHandler dataHandler =
            (PluginDataHandler) method.invoke(pluginDataHandlerProvider);
        try {
          dataDump.add(prettyStringifyMap(dataHandler.getAllValues()) + "\n");
        } catch (Exception e) {
          log.warn("Exception while retrieving data", e);
        }
      } catch (Exception e) {
        log.error("Error while invoking method: {}", method.getName(), e);
        throw new RuntimeException("Error while retrieving stored data", e);
      }
    }
  }
}
