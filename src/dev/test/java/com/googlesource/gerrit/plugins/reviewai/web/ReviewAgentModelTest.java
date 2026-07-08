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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.AccountGroup;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.InternalGroup;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.account.GroupMembership;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.permissions.DevAiAdministratorAccess;
import com.googlesource.gerrit.plugins.reviewai.settings.AiProviderType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ReviewAgentModelTest extends TestBase {
  @Mock private ChangeResource changeResource;
  @Mock private ConfigCreator configCreator;
  @Mock private Configuration config;
  @Mock private AiReviewPermission aiReviewPermission;
  @Mock private CurrentUser currentUser;
  @Mock private GroupCache groupCache;
  @Mock private PermissionBackend permissionBackend;

  private ReviewAgentModel view;

  @Before
  public void setUp() throws Exception {
    Change change =
        new Change(CHANGE_ID, Change.id(1), Account.id(100), BRANCH_NAME, Instant.now());
    when(changeResource.getChange()).thenReturn(change);
    when(changeResource.getProject()).thenReturn(PROJECT_NAME);
    when(changeResource.getUser()).thenReturn(currentUser);
    when(configCreator.createConfig(PROJECT_NAME, CHANGE_ID)).thenReturn(config);
    when(config.getAiAdministratorsGroup()).thenReturn("");
    when(aiReviewPermission.canAiReview(changeResource)).thenReturn(true);
    view =
        new ReviewAgentModel(
            configCreator,
            aiReviewPermission,
            new DevAiAdministratorAccess(groupCache, permissionBackend));
  }

  @Test
  public void exposesConfiguredProviderModelRoutes() throws Exception {
    when(config.getAiModels(false))
        .thenReturn(List.of("OpenAI/gpt-4.1", "MoonShot/moonshot-v1-8k"));
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "gpt-4.1"));

    Response<ReviewAgentModel.Output> response = view.apply(changeResource);

    assertEquals("OpenAI/gpt-4.1", response.value().defaultModelId);
    assertEquals(2, response.value().models.size());
    assertEquals("OpenAI/gpt-4.1", response.value().models.get(0).modelId);
    assertEquals("OpenAI", response.value().models.get(0).provider);
    assertEquals("gpt-4.1", response.value().models.get(0).model);
    assertEquals("MoonShot", response.value().models.get(1).provider);
    assertEquals("moonshot-v1-8k", response.value().models.get(1).model);
    assertTrue(response.value().canAiReview);
  }

  @Test
  public void exposesCanAiReviewFalseWhenPermissionIsDenied() throws Exception {
    when(config.getAiModels(false)).thenReturn(List.of("OpenAI/gpt-4.1"));
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "gpt-4.1"));
    when(aiReviewPermission.canAiReview(changeResource)).thenReturn(false);

    Response<ReviewAgentModel.Output> response = view.apply(changeResource);

    assertFalse(response.value().canAiReview);
  }

  @Test
  public void hidesMockModelRoutesFromNonAdministrators() throws Exception {
    when(config.getAiModels(false)).thenReturn(List.of("OpenAI/gpt-4.1"));
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "mock-ai"));
    when(config.getDefaultRealAiModelRoute())
        .thenReturn(Optional.of(new AiModelRoute(AiProviderType.OPENAI, "gpt-4.1")));

    Response<ReviewAgentModel.Output> response = view.apply(changeResource);

    assertEquals(1, response.value().models.size());
    assertEquals("OpenAI/gpt-4.1", response.value().models.get(0).modelId);
    assertEquals("OpenAI/gpt-4.1", response.value().defaultModelId);
  }

  @Test
  public void showsMockModelRoutesToConfiguredAiAdministrators() throws Exception {
    grantConfiguredAiAdministratorGroupPrivileges();
    when(config.getAiModels(true))
        .thenReturn(List.of("OpenAI/gpt-4.1", "OpenAI/mock-ai", "MoonShot/mock-ai"));
    when(config.getSelectedAiModelRoute())
        .thenReturn(new AiModelRoute(AiProviderType.OPENAI, "mock-ai"));

    Response<ReviewAgentModel.Output> response = view.apply(changeResource);

    assertEquals(3, response.value().models.size());
    assertEquals("OpenAI/mock-ai", response.value().defaultModelId);
    assertTrue(
        response.value().models.stream()
            .anyMatch(model -> "OpenAI/mock-ai".equals(model.modelId)));
    assertTrue(
        response.value().models.stream()
            .anyMatch(model -> "MoonShot/mock-ai".equals(model.modelId)));
  }

  private void grantConfiguredAiAdministratorGroupPrivileges() {
    when(config.getAiAdministratorsGroup()).thenReturn("AI Owners");
    AccountGroup.UUID administratorGroupUuid = AccountGroup.uuid("ai-administrators");
    InternalGroup administratorGroup = org.mockito.Mockito.mock(InternalGroup.class);
    GroupMembership groupMembership = org.mockito.Mockito.mock(GroupMembership.class);
    when(groupCache.get(AccountGroup.nameKey("AI Owners")))
        .thenReturn(Optional.of(administratorGroup));
    when(administratorGroup.getGroupUUID()).thenReturn(administratorGroupUuid);
    when(currentUser.getEffectiveGroups()).thenReturn(groupMembership);
    when(groupMembership.contains(administratorGroupUuid)).thenReturn(true);
  }
}
