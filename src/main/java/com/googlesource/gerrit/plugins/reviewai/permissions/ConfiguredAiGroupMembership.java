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

import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.InternalGroup;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupCache;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class ConfiguredAiGroupMembership {
  private final GroupCache groupCache;

  @Inject
  public ConfiguredAiGroupMembership(GroupCache groupCache) {
    this.groupCache = groupCache;
  }

  public boolean contains(String groupName, CurrentUser user) {
    return containsConfiguredGroup(groupName, user).orElse(false);
  }

  public Optional<Boolean> containsConfiguredGroup(String groupName, CurrentUser user) {
    try {
      if (groupName == null || groupName.isBlank() || user == null) {
        return Optional.empty();
      }
      return groupCache
          .get(AccountGroup.nameKey(groupName))
          .map(InternalGroup::getGroupUUID)
          .map(user.getEffectiveGroups()::contains);
    } catch (Exception e) {
      log.debug("Failed to inspect configured AI group membership", e);
      return Optional.empty();
    }
  }
}
