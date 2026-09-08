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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.InternalGroup;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.permissions.GlobalPermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DevAiRoleResolverTest {
  private static final String GROUP_NAME = "AI Owners";
  private static final AccountGroup.UUID GROUP_UUID = AccountGroup.uuid("ai-owners");

  @Mock private Configuration config;
  @Mock private GroupCache groupCache;
  @Mock private CurrentUser user;
  @Mock private InternalGroup group;
  @Mock private GroupMembership userGroups;
  @Mock private PermissionBackend permissionBackend;
  @Mock private PermissionBackend.WithUser permissionBackendWithUser;

  private DevAiRoleResolver roleResolver;

  @Before
  public void setUp() {
    roleResolver =
        new DevAiRoleResolver(
            new ConfiguredAiGroupMembership(groupCache), permissionBackend);
    when(config.getAiAdministratorsGroup()).thenReturn(GROUP_NAME);
  }

  @Test
  public void resolvesAdministratorForConfiguredGroupMember() {
    configureAdministratorGroupMembership(true);

    assertEquals(AiRole.ADMINISTRATOR, roleResolver.resolve(config, user));
    verifyNoInteractions(permissionBackend);
  }

  @Test
  public void configuredGroupNonMemberIsNotAdministratorDespiteGerritPermission() {
    configureAdministratorGroupMembership(false);

    assertEquals(AiRole.USER, roleResolver.resolve(config, user));
    verifyNoInteractions(permissionBackend);
  }

  @Test
  public void unknownConfiguredGroupFallsBackToGerritAdministratorPermission() throws Exception {
    when(groupCache.get(AccountGroup.nameKey(GROUP_NAME))).thenReturn(Optional.empty());
    when(permissionBackend.user(user)).thenReturn(permissionBackendWithUser);
    when(permissionBackendWithUser.test(GlobalPermission.ADMINISTRATE_SERVER)).thenReturn(true);

    assertEquals(AiRole.ADMINISTRATOR, roleResolver.resolve(config, user));
  }

  private void configureAdministratorGroupMembership(boolean member) {
    when(groupCache.get(AccountGroup.nameKey(GROUP_NAME))).thenReturn(Optional.of(group));
    when(group.getGroupUUID()).thenReturn(GROUP_UUID);
    when(user.getEffectiveGroups()).thenReturn(userGroups);
    when(userGroups.contains(GROUP_UUID)).thenReturn(member);
  }
}
