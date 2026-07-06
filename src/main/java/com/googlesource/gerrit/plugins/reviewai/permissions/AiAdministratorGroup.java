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

package com.googlesource.gerrit.plugins.reviewai.permissions;

import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.InternalGroup;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AiAdministratorGroup {
  private AiAdministratorGroup() {}

  public static Optional<Boolean> containsConfiguredGroup(
      Configuration config, GroupCache groupCache, CurrentUser user) {
    try {
      if (config == null || groupCache == null || user == null) {
        return Optional.empty();
      }
      String groupName = config.getAiAdministratorsGroup();
      if (groupName == null || groupName.isBlank()) {
        return Optional.empty();
      }
      return groupCache
          .get(AccountGroup.nameKey(groupName))
          .map(InternalGroup::getGroupUUID)
          .map(user.getEffectiveGroups()::contains);
    } catch (Exception e) {
      log.debug("Failed to inspect configured AI administrator group membership", e);
      return Optional.empty();
    }
  }

  public static boolean contains(Configuration config, GroupCache groupCache, CurrentUser user) {
    return containsConfiguredGroup(config, groupCache, user).orElse(false);
  }

  public static boolean isAdministrator(
      Configuration config,
      GroupCache groupCache,
      PermissionBackend permissionBackend,
      CurrentUser user) {
    Optional<Boolean> configuredGroupMember = containsConfiguredGroup(config, groupCache, user);
    if (configuredGroupMember.isPresent()) {
      return configuredGroupMember.get();
    }
    try {
      return user != null
          && permissionBackend != null
          && permissionBackend.user(user).test(GlobalPermission.ADMINISTRATE_SERVER);
    } catch (Exception e) {
      log.debug("Failed to inspect Gerrit administrative permission", e);
      return false;
    }
  }
}
