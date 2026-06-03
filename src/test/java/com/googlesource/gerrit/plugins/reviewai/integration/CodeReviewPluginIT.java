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

package com.googlesource.gerrit.plugins.reviewai.integration;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import lombok.extern.slf4j.Slf4j;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;

import static junit.framework.TestCase.assertNotNull;
import static org.mockito.Mockito.when;

@Ignore(
    "This test suite is designed to demonstrate how to test the Gerrit and AI interfaces in a real environment. "
        + "It is not intended to be executed during the regular build process")
@Slf4j
@RunWith(MockitoJUnitRunner.class)
public class CodeReviewPluginIT {
  @Mock private Configuration config;

  @Mock protected PluginDataHandlerProvider pluginDataHandlerProvider;

  @InjectMocks private GerritClient gerritClient;

  @Test
  public void getPatchSet() throws Exception {
    when(config.getGerritUserName()).thenReturn("Your Gerrit username");

    String patchSet = gerritClient.getPatchSet("${changeId}");
    log.info("patchSet: {}", patchSet);
    assertNotNull(patchSet);
  }

  @Test
  public void setReview() throws Exception {
    ChangeSetData changeSetData =
        new ChangeSetData(1, config.getVotingMinScore(), config.getVotingMaxScore());
    Localizer localizer = new Localizer(config);
    when(config.getGerritUserName()).thenReturn("Your Gerrit username");

    List<ReviewBatch> reviewBatches = new ArrayList<>();
    reviewBatches.add(new ReviewBatch("message"));

    GerritClientReview gerritClientReview =
        new GerritClientReview(config, pluginDataHandlerProvider, localizer);
    gerritClientReview.setReview(new GerritChange("Your changeId"), reviewBatches, changeSetData);
  }
}
