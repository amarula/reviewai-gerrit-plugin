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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.permissions.ChangePermission;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.PermissionBackendException;
import com.google.gerrit.server.query.change.ChangeData;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewPermissionTest extends TestBase {
  @Mock private PermissionBackend permissionBackend;
  @Mock private PermissionBackend.WithUser permissionsForUser;
  @Mock private PermissionBackend.ForChange permissionsForChange;
  @Mock private ChangeData.Factory changeDataFactory;
  @Mock private ChangeData changeData;
  @Mock private CurrentUser currentUser;
  @Mock private ChangeResource changeResource;

  private AiReviewPermission aiReviewPermission;
  private GerritChange change;

  @Before
  public void setUp() {
    aiReviewPermission = new AiReviewPermission(permissionBackend, changeDataFactory);
    change = getGerritChange();
    change.setChangeNumber(1);
  }

  @Test
  public void allowsRestRequestWhenGerritGrantsAiReview() throws Exception {
    when(changeResource.permissions()).thenReturn(permissionsForChange);
    when(permissionsForChange.test(ChangePermission.AI_REVIEW)).thenReturn(true);

    assertTrue(aiReviewPermission.canAiReview(changeResource));
    aiReviewPermission.checkCanAiReview(changeResource);
  }

  @Test
  public void deniesRestRequestWhenGerritDoesNotGrantAiReview() throws Exception {
    when(changeResource.permissions()).thenReturn(permissionsForChange);

    assertFalse(aiReviewPermission.canAiReview(changeResource));
    assertThrows(AuthException.class, () -> aiReviewPermission.checkCanAiReview(changeResource));
    verify(permissionsForChange, times(2)).test(ChangePermission.AI_REVIEW);
  }

  @Test
  public void deniesRestRequestWhenPermissionBackendFails() throws Exception {
    when(changeResource.permissions()).thenReturn(permissionsForChange);
    when(permissionsForChange.test(ChangePermission.AI_REVIEW))
        .thenThrow(new PermissionBackendException(new IllegalStateException()));

    assertThrows(AuthException.class, () -> aiReviewPermission.checkCanAiReview(changeResource));
  }

  @Test
  public void deniesRestRequestWhenPermissionsCannotBeLoaded() {
    when(changeResource.permissions()).thenThrow(new IllegalStateException());

    assertFalse(aiReviewPermission.canAiReview(changeResource));
    assertThrows(AuthException.class, () -> aiReviewPermission.checkCanAiReview(changeResource));
  }

  @Test
  public void allowsEventWhenGerritGrantsAiReviewToEventUser() throws Exception {
    setupEventPermissions();
    when(permissionsForChange.test(ChangePermission.AI_REVIEW)).thenReturn(true);

    assertTrue(aiReviewPermission.canAiReview(change, currentUser));
    verify(permissionBackend).user(currentUser);
    verify(permissionsForUser).change(changeData);
  }

  @Test
  public void deniesEventWhenGerritDoesNotGrantAiReview() throws Exception {
    setupEventPermissions();

    assertFalse(aiReviewPermission.canAiReview(change, currentUser));
    verify(permissionsForChange).test(ChangePermission.AI_REVIEW);
  }

  @Test
  public void deniesEventWhenPermissionBackendFails() throws Exception {
    setupEventPermissions();
    when(permissionsForChange.test(ChangePermission.AI_REVIEW))
        .thenThrow(new PermissionBackendException(new IllegalStateException()));

    assertFalse(aiReviewPermission.canAiReview(change, currentUser));
  }

  @Test
  public void deniesEventWhenChangeCannotBeLoaded() {
    when(changeDataFactory.create(PROJECT_NAME, Change.id(1)))
        .thenThrow(new IllegalStateException());

    assertFalse(aiReviewPermission.canAiReview(change, currentUser));
    verifyNoInteractions(permissionBackend);
  }

  @Test
  public void deniesEventWithoutAnAuthor() {
    assertFalse(aiReviewPermission.canAiReview(change, null));
    verifyNoInteractions(permissionBackend, changeDataFactory);
  }

  @Test
  public void deniesEventWithoutAChangeNumber() {
    change.setChangeNumber(null);

    assertFalse(aiReviewPermission.canAiReview(change, currentUser));
    verifyNoInteractions(permissionBackend, changeDataFactory);
  }

  private void setupEventPermissions() {
    when(changeDataFactory.create(PROJECT_NAME, Change.id(1))).thenReturn(changeData);
    when(permissionBackend.user(currentUser)).thenReturn(permissionsForUser);
    when(permissionsForUser.change(changeData)).thenReturn(permissionsForChange);
  }
}
