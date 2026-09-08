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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.InternalGroup;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupMembership;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ConfiguredAiGroupMembershipTest {
  private static final String GROUP_NAME = "AI Moderators";
  private static final AccountGroup.UUID GROUP_UUID = AccountGroup.uuid("ai-moderators");

  @Mock private GroupCache groupCache;
  @Mock private CurrentUser user;
  @Mock private InternalGroup group;
  @Mock private GroupMembership userGroups;

  private ConfiguredAiGroupMembership groupMembership;

  @Before
  public void setUp() {
    groupMembership = new ConfiguredAiGroupMembership(groupCache);
  }

  @Test
  public void containsReturnsTrueForConfiguredGroupMember() {
    when(groupCache.get(AccountGroup.nameKey(GROUP_NAME))).thenReturn(Optional.of(group));
    when(group.getGroupUUID()).thenReturn(GROUP_UUID);
    when(user.getEffectiveGroups()).thenReturn(userGroups);
    when(userGroups.contains(GROUP_UUID)).thenReturn(true);

    assertTrue(groupMembership.contains(GROUP_NAME, user));
  }

  @Test
  public void containsReturnsFalseForNonMember() {
    when(groupCache.get(AccountGroup.nameKey(GROUP_NAME))).thenReturn(Optional.of(group));
    when(group.getGroupUUID()).thenReturn(GROUP_UUID);
    when(user.getEffectiveGroups()).thenReturn(userGroups);

    assertFalse(groupMembership.contains(GROUP_NAME, user));
  }

  @Test
  public void containsReturnsFalseForUnknownGroup() {
    when(groupCache.get(AccountGroup.nameKey(GROUP_NAME))).thenReturn(Optional.empty());

    assertFalse(groupMembership.contains(GROUP_NAME, user));
  }
}
