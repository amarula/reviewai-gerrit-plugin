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

package com.googlesource.gerrit.plugins.reviewai.web;

import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import lombok.extern.slf4j.Slf4j;

@Singleton
@Slf4j
public class AiReviewPermission {
  private final PermissionBackend permissionBackend;
  private final ChangeData.Factory changeDataFactory;

  @Inject
  AiReviewPermission(PermissionBackend permissionBackend, ChangeData.Factory changeDataFactory) {
    this.permissionBackend = permissionBackend;
    this.changeDataFactory = changeDataFactory;
  }

  public Boolean canAiReview(ChangeResource resource) {
    try {
      return resource.permissions().test(ChangePermission.AI_REVIEW);
    } catch (PermissionBackendException | RuntimeException e) {
      log.warn("Failed to check AI review permission", e);
      return false;
    }
  }

  public void checkCanAiReview(ChangeResource resource) throws AuthException {
    if (!canAiReview(resource)) {
      throw new AuthException("AI review is not allowed for this change");
    }
  }

  public boolean canAiReview(GerritChange change, CurrentUser user) {
    if (user == null) {
      return false;
    }
    try {
      var changeNumber = change.getChangeNumber();
      if (changeNumber.isEmpty()) {
        return false;
      }
      ChangeData changeData =
          changeDataFactory.create(change.getProjectNameKey(), Change.id(changeNumber.get()));
      return permissionBackend.user(user).change(changeData).test(ChangePermission.AI_REVIEW);
    } catch (PermissionBackendException | RuntimeException e) {
      log.warn("Failed to check AI review permission for change {}", change.getFullChangeId(), e);
      return false;
    }
  }
}
