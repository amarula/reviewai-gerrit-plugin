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
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.change.ChangeResource;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.time.Instant;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewMessageTest extends TestBase {
  @Mock private ChangeResource changeResource;
  @Mock private ConfigCreator configCreator;
  @Mock private Configuration config;
  @Mock private GerritApi gerritApi;
  @Mock private AiReviewPermission aiReviewPermission;

  private AiReviewMessage view;

  @Before
  public void setUp() throws Exception {
    Change change =
        new Change(CHANGE_ID, Change.id(1), Account.id(100), BRANCH_NAME, Instant.now());
    when(changeResource.getChange()).thenReturn(change);
    when(changeResource.getProject()).thenReturn(PROJECT_NAME);
    when(configCreator.createConfig(PROJECT_NAME, CHANGE_ID)).thenReturn(config);
    view = new AiReviewMessage(configCreator, gerritApi, aiReviewPermission);
  }

  @Test(expected = AuthException.class)
  public void rejectsMessageWhenCanAiReviewIsFalse() throws Exception {
    AiReviewMessage.Input input = new AiReviewMessage.Input();
    input.message = "/review";
    doThrow(new AuthException("AI review is not allowed for this change"))
        .when(aiReviewPermission)
        .checkCanAiReview(changeResource);

    view.apply(changeResource, input);
  }
}
