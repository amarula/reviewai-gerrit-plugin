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
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.LabelId;
import com.google.gerrit.entities.Project;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.LabelPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.query.change.ChangeData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DefaultAiRoleResolverTest {
  private static final Project.NameKey PROJECT = Project.nameKey("test/project");
  private static final Change.Id CHANGE_ID = Change.id(1);
  private static final LabelPermission.WithValue CODE_REVIEW_PLUS_TWO =
      new LabelPermission.WithValue(LabelId.CODE_REVIEW, (short) 2);

  @Mock private Configuration config;
  @Mock private CurrentUser user;
  @Mock private PermissionBackend permissionBackend;
  @Mock private PermissionBackend.WithUser permissionBackendWithUser;
  @Mock private PermissionBackend.ForChange permissionBackendForChange;
  @Mock private ChangeData.Factory changeDataFactory;
  @Mock private ChangeData changeData;

  private DefaultAiRoleResolver roleResolver;

  @Before
  public void setUp() {
    roleResolver = new DefaultAiRoleResolver(permissionBackend, changeDataFactory);
    when(changeDataFactory.create(PROJECT, CHANGE_ID)).thenReturn(changeData);
    when(permissionBackend.user(user)).thenReturn(permissionBackendWithUser);
    when(permissionBackendWithUser.change(changeData)).thenReturn(permissionBackendForChange);
  }

  @Test
  public void resolvesModeratorWhenUserCanApplyCodeReviewPlusTwo() {
    when(permissionBackendForChange.testOrFalse(CODE_REVIEW_PLUS_TWO)).thenReturn(true);

    assertEquals(AiRole.MODERATOR, roleResolver.resolve(config, user, PROJECT, CHANGE_ID));
  }

  @Test
  public void resolvesModeratorWhenUserCanSubmit() {
    when(permissionBackendForChange.testOrFalse(ChangePermission.SUBMIT)).thenReturn(true);

    assertEquals(AiRole.MODERATOR, roleResolver.resolve(config, user, PROJECT, CHANGE_ID));
  }

  @Test
  public void resolvesUserWhenUserHasNeitherModeratorPermission() {
    assertEquals(AiRole.USER, roleResolver.resolve(config, user, PROJECT, CHANGE_ID));
  }

  @Test
  public void resolvesUserWhenChangeContextIsMissing() {
    assertEquals(AiRole.USER, roleResolver.resolve(config, user, PROJECT, null));
  }

  @Test
  public void resolvesUserWhenPermissionLookupFails() {
    when(changeDataFactory.create(PROJECT, CHANGE_ID))
        .thenThrow(new IllegalStateException("change unavailable"));

    assertEquals(AiRole.USER, roleResolver.resolve(config, user, PROJECT, CHANGE_ID));
  }
}
