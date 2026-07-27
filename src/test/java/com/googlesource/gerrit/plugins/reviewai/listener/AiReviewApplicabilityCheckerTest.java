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

package com.googlesource.gerrit.plugins.reviewai.listener;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.common.SubmitRequirementInput;
import com.google.gerrit.extensions.common.SubmitRequirementResultInfo;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import org.junit.Before;
import org.junit.Test;

public class AiReviewApplicabilityCheckerTest {
  private static final String PROJECT = "test/project";
  private static final String BRANCH = "main";
  private static final String CHANGE_ID = "I0123456789abcdef";
  private static final String EXPRESSION = "label:Verified>=1";

  private Configuration config;
  private ChangeApi changeApi;
  private GerritChange change;
  private AiReviewApplicabilityChecker checker;

  @Before
  public void setUp() throws Exception {
    config = mock(Configuration.class);
    GerritApi gerritApi = mock(GerritApi.class);
    Changes changes = mock(Changes.class);
    changeApi = mock(ChangeApi.class);
    change =
        new GerritChange(
            Project.nameKey(PROJECT),
            BranchNameKey.create(Project.nameKey(PROJECT), "refs/heads/" + BRANCH),
            Change.key(CHANGE_ID));

    when(config.getGerritApi()).thenReturn(gerritApi);
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id(PROJECT, BRANCH, CHANGE_ID)).thenReturn(changeApi);
    checker = new AiReviewApplicabilityChecker(config);
  }

  @Test
  public void missingExpressionIsApplicable() throws Exception {
    assertTrue(checker.isApplicable(change, ""));

    verify(changeApi, never()).checkSubmitRequirement(any());
  }

  @Test
  public void satisfiedExpressionIsApplicable() throws Exception {
    when(changeApi.checkSubmitRequirement(any())).thenReturn(resultWithStatus("SATISFIED"));

    assertTrue(checker.isApplicable(change, EXPRESSION));
  }

  @Test
  public void notApplicableExpressionIsNotApplicable() throws Exception {
    when(changeApi.checkSubmitRequirement(any())).thenReturn(resultWithStatus("NOT_APPLICABLE"));

    assertFalse(checker.isApplicable(change, EXPRESSION));
  }

  @Test
  public void evaluationErrorFailsClosed() throws Exception {
    when(changeApi.checkSubmitRequirement(any())).thenReturn(resultWithStatus("ERROR"));

    assertFalse(checker.isApplicable(change, EXPRESSION));
  }

  @Test
  public void apiErrorFailsClosed() throws Exception {
    when(changeApi.checkSubmitRequirement(any())).thenThrow(new BadRequestException("failed"));

    assertFalse(checker.isApplicable(change, EXPRESSION));
  }

  private static SubmitRequirementResultInfo resultWithStatus(String status) {
    SubmitRequirementResultInfo result = new SubmitRequirementResultInfo();
    result.status = SubmitRequirementResultInfo.Status.valueOf(status);
    return result;
  }
}
