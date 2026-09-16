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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.BaseOptionSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import java.util.Map;
import org.junit.Test;

public class AiRolePolicyTest {
  @Test
  public void moderatorCanForgetConversationButCannotUseAdministratorFeatures() {
    assertTrue(AiRolePolicy.isAllowed(AiRole.MODERATOR, AiAction.USE_MODERATOR_FEATURES));
    assertFalse(AiRolePolicy.isAllowed(AiRole.MODERATOR, AiAction.USE_ADMINISTRATOR_FEATURES));
  }

  @Test
  public void administratorInheritsModeratorPermissions() {
    assertTrue(AiRolePolicy.isAllowed(AiRole.ADMINISTRATOR, AiAction.USE_MODERATOR_FEATURES));
    assertTrue(AiRolePolicy.isAllowed(AiRole.ADMINISTRATOR, AiAction.USE_ADMINISTRATOR_FEATURES));
  }

  @Test
  public void commandActionsAreMappedCentrally() {
    assertEquals(
        AiAction.USE_MODERATOR_FEATURES,
        AiCommandAccessPolicy.requiredAction(CommandSet.FORGET_THREAD, Map.of()).orElseThrow());
    assertEquals(
        AiAction.USE_ADMINISTRATOR_FEATURES,
        AiCommandAccessPolicy.requiredAction(CommandSet.REVIEW, Map.of(BaseOptionSet.DEBUG, ""))
            .orElseThrow());
  }
}
