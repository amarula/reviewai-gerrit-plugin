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

package com.googlesource.gerrit.plugins.reviewai.permissions;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.BaseOptionSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import java.util.Map;
import java.util.Optional;

public final class AiCommandAccessPolicy {
  private static final Map<CommandSet, AiAction> COMMAND_ACTIONS =
      Map.of(
          CommandSet.DIRECTIVES, AiAction.USE_ADMINISTRATOR_FEATURES,
          CommandSet.CONFIGURE, AiAction.USE_ADMINISTRATOR_FEATURES,
          CommandSet.SHOW, AiAction.USE_ADMINISTRATOR_FEATURES);

  private AiCommandAccessPolicy() {}

  public static Optional<AiAction> requiredAction(
      CommandSet command, Map<BaseOptionSet, String> options) {
    if (command == CommandSet.REVIEW && options.containsKey(BaseOptionSet.DEBUG)) {
      return Optional.of(AiAction.USE_ADMINISTRATOR_FEATURES);
    }
    return Optional.ofNullable(COMMAND_ACTIONS.get(command));
  }

  public static Optional<AiRole> deniedRequiredRole(
      AiRole role, CommandSet command, Map<BaseOptionSet, String> options) {
    return requiredAction(command, options)
        .filter(action -> !AiRolePolicy.isAllowed(role, action))
        .map(AiRolePolicy::requiredRole);
  }
}
