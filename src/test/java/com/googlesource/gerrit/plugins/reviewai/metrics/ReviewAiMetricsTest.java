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

package com.googlesource.gerrit.plugins.reviewai.metrics;

import static org.junit.Assert.assertEquals;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import org.junit.Test;

public class ReviewAiMetricsTest {
  @Test
  public void specializedAgentStageIncludesAgentName() {
    assertEquals(
        "REVIEW_SPECIALIZED_AGENT_CODE_QUALITY",
        ReviewAiMetrics.aiRequestStageLabel(
            ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT, "CODE_QUALITY"));
  }

  @Test
  public void specializedAgentStageWithoutAgentNameUsesGenericStage() {
    assertEquals(
        "REVIEW_SPECIALIZED_AGENT",
        ReviewAiMetrics.aiRequestStageLabel(
            ReviewAssistantStage.REVIEW_SPECIALIZED_AGENT, " "));
  }

  @Test
  public void nonSpecializedStageIgnoresAgentName() {
    assertEquals(
        "REVIEW_SPECIALIZED_TRIAGE",
        ReviewAiMetrics.aiRequestStageLabel(
            ReviewAssistantStage.REVIEW_SPECIALIZED_TRIAGE, "CODE_QUALITY"));
  }
}
