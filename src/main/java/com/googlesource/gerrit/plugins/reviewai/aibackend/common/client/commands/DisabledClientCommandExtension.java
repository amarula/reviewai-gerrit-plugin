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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands;

import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.commands.IPatchSetProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.util.Map;
import java.util.Optional;

@Singleton
public class DisabledClientCommandExtension implements ClientCommandExtension {
  @Override
  public boolean requiresAdministrator(
      ClientCommandBase.CommandSet command,
      Map<ClientCommandBase.BaseOptionSet, String> baseOptions) {
    return false;
  }

  @Override
  public boolean acceptsDynamicOptions(ClientCommandBase.CommandSet command) {
    return false;
  }

  @Override
  public boolean dynamicOptionsMismatch(
      Configuration config,
      ChangeSetData changeSetData,
      Localizer localizer,
      Map<ClientCommandBase.BaseOptionSet, String> baseOptions,
      Map<String, String> dynamicOptions) {
    return false;
  }

  @Override
  public boolean applyReviewDebugOption(
      ChangeSetData changeSetData, Map<ClientCommandBase.BaseOptionSet, String> baseOptions) {
    return false;
  }

  @Override
  public boolean executeCommand(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer,
      IPatchSetProvider patchSetProvider,
      ClientCommandBase.CommandSet command,
      Map<ClientCommandBase.BaseOptionSet, String> baseOptions,
      Map<String, String> dynamicOptions,
      String nextString) {
    return false;
  }

  @Override
  public Optional<String> getDynamicConfigurationMessage(
      Configuration config,
      PluginDataHandlerProvider pluginDataHandlerProvider,
      Localizer localizer) {
    return Optional.empty();
  }
}
