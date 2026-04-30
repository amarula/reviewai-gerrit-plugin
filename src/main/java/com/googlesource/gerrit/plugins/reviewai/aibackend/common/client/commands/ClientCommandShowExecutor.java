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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.commands.IPatchSetProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksConfiguration;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug.DebugCodeBlocksDataDump;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.messages.debug.DebugCodeBlocksPromptingParamInstructions;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.messages.debug.DebugCodeBlocksPromptingParamPrompts;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithDoubleNewLine;

@Slf4j
public class ClientCommandShowExecutor extends ClientCommandBase {
  private final ChangeSetData changeSetData;
  private final GerritChange change;
  private final ICodeContextPolicy codeContextPolicy;
  private final Localizer localizer;
  private final PluginDataHandlerProvider pluginDataHandlerProvider;
  private final IPatchSetProvider IPatchSetProvider;
  private final List<String> itemsToShow = new ArrayList<>();

  public ClientCommandShowExecutor(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      IPatchSetProvider IPatchSetProvider) {
    super(config);
    this.localizer = localizer;
    this.change = change;
    this.changeSetData = changeSetData;
    this.codeContextPolicy = codeContextPolicy;
    this.pluginDataHandlerProvider = pluginDataHandlerProvider;
    this.IPatchSetProvider = IPatchSetProvider;
    log.debug("ClientShowCommandExecutor initialized.");
  }

  public void executeShowCommand(Map<BaseOptionSet, String> baseOptions) {
    log.debug("Executing Show Command: {}", baseOptions);
    for (BaseOptionSet baseOption : baseOptions.keySet()) {
      switch (baseOption) {
        case CONFIG -> commandDumpConfig();
        case LOCAL_DATA -> commandDumpStoredData();
        case PROMPTS -> commandShowPrompts(baseOptions);
        case INSTRUCTIONS -> commandShowInstructions(baseOptions);
        case SCOPE -> {}
      }
    }
    changeSetData.setReviewSystemMessage(joinWithDoubleNewLine(itemsToShow));
  }

  private void commandDumpConfig() {
    DebugCodeBlocksConfiguration debugCodeBlocksConfiguration =
        new DebugCodeBlocksConfiguration(localizer);
    itemsToShow.add(debugCodeBlocksConfiguration.getDebugCodeBlock(config));
  }

  private void commandDumpStoredData() {
    DebugCodeBlocksDataDump debugCodeBlocksDataDump =
        new DebugCodeBlocksDataDump(localizer, pluginDataHandlerProvider);
    itemsToShow.add(debugCodeBlocksDataDump.getDebugCodeBlock());
  }

  private void commandShowPrompts(Map<BaseOptionSet, String> baseOptions) {
    String patchSet = "";
    if (IPatchSetProvider != null) {
      try {
        patchSet = IPatchSetProvider.getPatchSet();
      } catch (Exception e) {
        log.error("Could not retrieve Patch Set for prompt debug output", e);
      }
    }
    DebugCodeBlocksPromptingParamPrompts debugCodeBlocksPromptingParamPrompts =
        new DebugCodeBlocksPromptingParamPrompts(
            localizer,
            config,
            changeSetData,
            change,
            codeContextPolicy,
            patchSet,
            getReviewScope(baseOptions));
    itemsToShow.add(debugCodeBlocksPromptingParamPrompts.getDebugCodeBlock());
  }

  private void commandShowInstructions(Map<BaseOptionSet, String> baseOptions) {
    DebugCodeBlocksPromptingParamInstructions debugCodeBlocksPromptingParamInstructions =
        new DebugCodeBlocksPromptingParamInstructions(
            localizer,
            config,
            changeSetData,
            change,
            codeContextPolicy,
            getReviewScope(baseOptions));
    itemsToShow.add(debugCodeBlocksPromptingParamInstructions.getDebugCodeBlock());
  }

  private ReviewScope getReviewScope(Map<BaseOptionSet, String> baseOptions) {
    if (!baseOptions.containsKey(BaseOptionSet.SCOPE)) {
      return null;
    }
    return ReviewScope.fromCommandOption(baseOptions.get(BaseOptionSet.SCOPE));
  }
}
