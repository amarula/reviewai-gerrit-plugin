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

import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.restapi.Response;
import com.google.gerrit.server.change.ChangeResource;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings.AiBackends;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings.LangChainProviders;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class ReviewAgentModelTest extends TestBase {
  @Mock private ChangeResource changeResource;
  @Mock private ConfigCreator configCreator;
  @Mock private Configuration config;
  @Mock private AiReviewPermission aiReviewPermission;

  private ReviewAgentModel view;

  @Before
  public void setUp() throws Exception {
    Change change =
        new Change(CHANGE_ID, Change.id(1), Account.id(100), BRANCH_NAME, Instant.now());
    when(changeResource.getChange()).thenReturn(change);
    when(changeResource.getProject()).thenReturn(PROJECT_NAME);
    when(configCreator.createConfig(PROJECT_NAME, CHANGE_ID)).thenReturn(config);
    when(aiReviewPermission.canAiReview(changeResource)).thenReturn(true);
    view = new ReviewAgentModel(configCreator, aiReviewPermission);
  }

  @Test
  public void openAiBackendUsesOpenAiProvider() throws Exception {
    when(config.getAiBackend()).thenReturn(AiBackends.OPENAI);
    when(config.getAiModel()).thenReturn("gpt-4.1");

    Response<ReviewAgentModel.Output> response = view.apply(changeResource);

    assertEquals("OPENAI", response.value().aiBackend);
    assertEquals("OPENAI", response.value().provider);
    assertEquals("gpt-4.1", response.value().aiModel);
    assertTrue(response.value().canAiReview);
  }

  @Test
  public void langChainBackendUsesConfiguredLangChainProvider() throws Exception {
    when(config.getAiBackend()).thenReturn(AiBackends.LANGCHAIN);
    when(config.getLcProvider()).thenReturn(LangChainProviders.GEMINI);
    when(config.getAiModel()).thenReturn("gemini-2.5-pro");

    Response<ReviewAgentModel.Output> response = view.apply(changeResource);

    assertEquals("LANGCHAIN", response.value().aiBackend);
    assertEquals("GEMINI", response.value().provider);
    assertEquals("gemini-2.5-pro", response.value().aiModel);
    assertTrue(response.value().canAiReview);
  }

  @Test
  public void exposesCanAiReviewFalseWhenPermissionIsDenied() throws Exception {
    when(config.getAiBackend()).thenReturn(AiBackends.OPENAI);
    when(config.getAiModel()).thenReturn("gpt-4.1");
    when(aiReviewPermission.canAiReview(changeResource)).thenReturn(false);

    Response<ReviewAgentModel.Output> response = view.apply(changeResource);

    assertFalse(response.value().canAiReview);
  }
}
