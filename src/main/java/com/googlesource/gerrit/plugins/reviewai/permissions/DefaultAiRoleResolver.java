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
import com.google.gerrit.entities.LabelId;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.LabelPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class DefaultAiRoleResolver implements AiRoleResolver {
  private static final LabelPermission.WithValue CODE_REVIEW_PLUS_TWO =
      new LabelPermission.WithValue(LabelId.CODE_REVIEW, (short) 2);

  protected final PermissionBackend permissionBackend;
  private final ChangeData.Factory changeDataFactory;

  @Inject
  public DefaultAiRoleResolver(
      PermissionBackend permissionBackend, ChangeData.Factory changeDataFactory) {
    this.permissionBackend = permissionBackend;
    this.changeDataFactory = changeDataFactory;
  }

  @Override
  public AiRole resolve(
      Configuration config, CurrentUser user, Project.NameKey project, Change.Id changeId) {
    if (user == null || project == null || changeId == null) {
      return AiRole.USER;
    }
    try {
      ChangeData changeData = changeDataFactory.create(project, changeId);
      if (changeData != null
          && hasModeratorPermission(permissionBackend.user(user).change(changeData))) {
        return AiRole.MODERATOR;
      }
    } catch (Exception e) {
      log.debug("Failed to inspect moderator permissions", e);
    }
    return AiRole.USER;
  }

  private boolean hasModeratorPermission(PermissionBackend.ForChange permissions) {
    return permissions.testOrFalse(CODE_REVIEW_PLUS_TWO)
        || permissions.testOrFalse(ChangePermission.SUBMIT);
  }
}
