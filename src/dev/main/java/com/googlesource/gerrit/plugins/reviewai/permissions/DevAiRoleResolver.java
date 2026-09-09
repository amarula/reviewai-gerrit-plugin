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

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class DevAiRoleResolver extends DefaultAiRoleResolver {
  private final ConfiguredAiGroupMembership groupMembership;

  @Inject
  public DevAiRoleResolver(
      ConfiguredAiGroupMembership groupMembership,
      PermissionBackend permissionBackend,
      ChangeData.Factory changeDataFactory) {
    super(permissionBackend, changeDataFactory);
    this.groupMembership = groupMembership;
  }

  @Override
  public AiRole resolve(
      Configuration config, CurrentUser user, Project.NameKey project, Change.Id changeId) {
    Optional<Boolean> configuredAdministrator =
        config == null
            ? Optional.empty()
            : groupMembership.containsConfiguredGroup(config.getAiAdministratorsGroup(), user);
    if (configuredAdministrator.orElse(false)
        || configuredAdministrator.isEmpty() && isGerritAdministrator(user)) {
      return AiRole.ADMINISTRATOR;
    }
    return super.resolve(config, user, project, changeId);
  }

  private boolean isGerritAdministrator(CurrentUser user) {
    try {
      return user != null
          && permissionBackend.user(user).test(GlobalPermission.ADMINISTRATE_SERVER);
    } catch (Exception e) {
      log.debug("Failed to inspect Gerrit administrative permission", e);
      return false;
    }
  }
}
