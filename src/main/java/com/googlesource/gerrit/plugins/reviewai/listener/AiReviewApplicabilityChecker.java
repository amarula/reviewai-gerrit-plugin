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

import com.google.gerrit.extensions.common.SubmitRequirementInput;
import com.google.gerrit.extensions.common.SubmitRequirementResultInfo;
import com.google.gerrit.extensions.restapi.ResourceNotFoundException;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.inject.Inject;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import lombok.extern.slf4j.Slf4j;

/** Evaluates whether Gerrit's configured change conditions allow an AI review to start. */
@Slf4j
public class AiReviewApplicabilityChecker {
  private static final String REQUIREMENT_NAME = "AI-Review-Trigger";
  private static final String ALWAYS_SUBMITTABLE = "is:true";

  private final Configuration config;

  @Inject
  public AiReviewApplicabilityChecker(Configuration config) {
    this.config = config;
  }

  /** Returns {@code true} when no expression is configured or Gerrit evaluates it as applicable. */
  public boolean isApplicable(GerritChange change, String expression) {
    if (expression == null || expression.isBlank()) {
      return true;
    }

    SubmitRequirementInput input = new SubmitRequirementInput();
    input.name = REQUIREMENT_NAME;
    input.applicabilityExpression = expression;
    input.submittabilityExpression = ALWAYS_SUBMITTABLE;

    SubmitRequirementResultInfo result;
    try (ManualRequestContext ignored = config.openRequestContext()) {
      result = change.getChangeApi(config).checkSubmitRequirement(input);
    } catch (ResourceNotFoundException e) {
      log.debug(
          "AI review applicability check skipped: change {} not accessible",
          change.getFullChangeId());
      return false;
    } catch (Exception e) {
      log.error(
          "Could not evaluate AI review applicability expression '{}' for change {}",
          expression,
          change.getFullChangeId(),
          e);
      return false;
    }

    if (result == null || result.status == null) {
      log.error(
          "Gerrit returned no status for AI review applicability expression '{}' on change {}",
          expression,
          change.getFullChangeId());
      return false;
    }
    if (result.status == SubmitRequirementResultInfo.Status.SATISFIED) {
      return true;
    }
    if (result.status != SubmitRequirementResultInfo.Status.NOT_APPLICABLE) {
      log.error(
          "AI review applicability check returned unexpected Gerrit submit-requirement status '{}' "
              + "evaluated to {} for change {}",
          result.status,
          expression,
          change.getFullChangeId());
    }
    return false;
  }
}
