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

import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;

@Singleton
public class DevAiAdministratorAccess implements AiAdministratorAccess {
  private final GroupCache groupCache;
  private final PermissionBackend permissionBackend;

  @Inject
  public DevAiAdministratorAccess(GroupCache groupCache, PermissionBackend permissionBackend) {
    this.groupCache = groupCache;
    this.permissionBackend = permissionBackend;
  }

  @Override
  public boolean isAdministrator(Configuration config, CurrentUser user) {
    return AiAdministratorGroup.isAdministrator(config, groupCache, permissionBackend, user);
  }
}
