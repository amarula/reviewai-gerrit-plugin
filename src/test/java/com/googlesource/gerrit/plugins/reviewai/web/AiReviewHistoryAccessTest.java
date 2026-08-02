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

import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.change.ChangeResource;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritAiReviewHistoryCollector;
import com.googlesource.gerrit.plugins.reviewai.config.ConfigCreator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.doThrow;

@RunWith(MockitoJUnitRunner.class)
public class AiReviewHistoryAccessTest {
  @Mock private ChangeResource changeResource;
  @Mock private ConfigCreator configCreator;
  @Mock private GerritAiReviewHistoryCollector collector;
  @Mock private AiReviewPermission aiReviewPermission;

  @Test(expected = AuthException.class)
  public void rejectsHistoryAccessWhenAiReviewIsNotAllowed() throws Exception {
    AiReviewHistory view = new AiReviewHistory(configCreator, collector, aiReviewPermission);
    doThrow(new AuthException("AI review is not allowed for this change"))
        .when(aiReviewPermission)
        .checkCanAiReview(changeResource);

    view.apply(changeResource);
  }
}
