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

package com.googlesource.gerrit.plugins.reviewai.web;

import com.google.gerrit.entities.AccessSection;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.GroupReference;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.PermissionRule;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;
import com.google.gerrit.server.project.SectionMatcher;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewPermissionTest extends TestBase {
  private static final AccountGroup.UUID DUMMY_GROUP_UUID = AccountGroup.uuid("dummy-group");
  private static final GroupReference DUMMY_GROUP =
      GroupReference.create(DUMMY_GROUP_UUID, "Dummy");

  @Mock private ProjectCache projectCache;
  @Mock private ProjectState projectState;
  @Mock private SectionMatcher sectionMatcher;
  @Mock private CurrentUser currentUser;
  @Mock private GroupMembership groupMembership;
  @Mock private ChangeResource changeResource;

  private AiReviewPermission aiReviewPermission;

  @Before
  public void setUp() {
    aiReviewPermission = new AiReviewPermission(projectCache);
  }

  @Test
  public void refNameCandidatesIncludesFullRefsHeadsNameForShortBranch() {
    assertEquals(
        Set.of("myBranchName", "refs/heads/myBranchName"),
        AiReviewPermission.refNameCandidates("myBranchName"));
  }

  @Test
  public void missingAiReviewRuleAllowsReviewByDefault() {
    AccessSection accessSection = AccessSection.builder("refs/heads/*").build();
    setupMatchingAccessSection(accessSection);

    assertFalse(
        aiReviewPermission.isAiReviewExplicitlyDisallowed(PROJECT_NAME, "myBranchName"));
  }

  @Test
  public void allowAiReviewRuleAllowsReview() {
    setupMatchingAccessSection(accessSectionWithRule(PermissionRule.Action.ALLOW));

    assertFalse(
        aiReviewPermission.isAiReviewExplicitlyDisallowed(PROJECT_NAME, "myBranchName"));
  }

  @Test
  public void denyAiReviewRuleDisallowsReview() {
    setupMatchingAccessSection(accessSectionWithRule(PermissionRule.Action.DENY));

    assertTrue(aiReviewPermission.isAiReviewExplicitlyDisallowed(PROJECT_NAME, "myBranchName"));
  }

  @Test
  public void blockAiReviewRuleDisallowsReview() {
    setupMatchingAccessSection(accessSectionWithRule(PermissionRule.Action.BLOCK));

    assertTrue(aiReviewPermission.isAiReviewExplicitlyDisallowed(PROJECT_NAME, "myBranchName"));
  }

  @Test
  public void denyAiReviewRuleForUnrelatedGroupAllowsReviewForCurrentUser() {
    setupMatchingAccessSection(accessSectionWithRule(PermissionRule.Action.DENY), currentUser);
    when(currentUser.getEffectiveGroups()).thenReturn(groupMembership);
    when(groupMembership.contains(DUMMY_GROUP_UUID)).thenReturn(false);

    assertFalse(
        aiReviewPermission.isAiReviewExplicitlyDisallowed(
            PROJECT_NAME, "myBranchName", currentUser));
  }

  @Test
  public void denyAiReviewRuleForUserGroupDisallowsReviewForCurrentUser() {
    setupMatchingAccessSection(accessSectionWithRule(PermissionRule.Action.DENY), currentUser);
    when(currentUser.getEffectiveGroups()).thenReturn(groupMembership);
    when(groupMembership.contains(DUMMY_GROUP_UUID)).thenReturn(true);

    assertTrue(
        aiReviewPermission.isAiReviewExplicitlyDisallowed(
            PROJECT_NAME, "myBranchName", currentUser));
  }

  @Test
  public void canAiReviewIgnoresAllowRuleForUnrelatedGroup() {
    setupMatchingAccessSection(accessSectionWithRule(PermissionRule.Action.ALLOW), currentUser);
    setupChangeResource(currentUser);

    assertTrue(aiReviewPermission.canAiReview(changeResource));
  }

  private void setupMatchingAccessSection(AccessSection accessSection) {
    setupMatchingAccessSection(accessSection, null);
  }

  private void setupMatchingAccessSection(AccessSection accessSection, CurrentUser user) {
    when(projectCache.get(PROJECT_NAME)).thenReturn(Optional.of(projectState));
    when(projectState.getAllSections()).thenReturn(List.of(sectionMatcher));
    when(sectionMatcher.match("refs/heads/myBranchName", user)).thenReturn(true);
    when(sectionMatcher.getSection()).thenReturn(accessSection);
  }

  private AccessSection accessSectionWithRule(PermissionRule.Action action) {
    return AccessSection.builder("refs/heads/*")
        .addPermission(
            Permission.builder(AiReviewPermission.AI_REVIEW_ACCESS_PERMISSION)
                .add(
                    PermissionRule.builder(DUMMY_GROUP)
                        .setAction(action)))
        .build();
  }

  private void setupChangeResource(CurrentUser user) {
    Change change =
        new Change(CHANGE_ID, Change.id(1), Account.id(100), BRANCH_NAME, Instant.now());
    when(changeResource.getProject()).thenReturn(PROJECT_NAME);
    when(changeResource.getChange()).thenReturn(change);
    when(changeResource.getUser()).thenReturn(user);
  }
}
