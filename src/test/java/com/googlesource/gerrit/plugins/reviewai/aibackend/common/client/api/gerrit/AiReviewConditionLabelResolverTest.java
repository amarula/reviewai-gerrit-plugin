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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import static org.junit.Assert.assertEquals;

import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.LabelInfo;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiReviewConditionLabelResolverTest {
  private static final String CHANGE_ID = "project~main~I123";

  @Test
  public void resolvesEveryLabelReferencedByCondition() {
    AiReviewConditionLabelResolver resolver = new AiReviewConditionLabelResolver();
    resolver.cacheCurrentValues(
        CHANGE_ID, Map.of("Verified", label(1), "Code-Review", label(2)));

    assertEquals(
        Map.of(
            "Verified",
            List.of((short) 1),
            "Code-Review",
            List.of((short) 2),
            "Build-Check",
            List.of()),
        resolver.resolve(
            CHANGE_ID,
            "label:Verified=+1 OR (label:\"Code-Review>=2\" AND -label:Build-Check-1)"));
  }

  @Test
  public void valuesAreDistinctAndDoNotContainAccounts() {
    AiReviewConditionLabelResolver resolver = new AiReviewConditionLabelResolver();
    resolver.cacheCurrentValues(
        CHANGE_ID,
        Map.of(
            "Verified",
            label(
                approval(1001, -1), approval(1002, 1), approval(1003, 1))));

    assertEquals(
        Map.of("Verified", List.of((short) -1, (short) 1)),
        resolver.resolve(CHANGE_ID, "label:Verified=+1"));
  }

  @Test
  public void formatsValuesAndMissingVotesForPrompt() {
    assertEquals(
        "- Code-Review: no vote\n- Verified: -1, +1",
        AiReviewConditionLabelResolver.formatConditionLabelValues(
            Map.of(
                "Verified",
                List.of((short) -1, (short) 1),
                "Code-Review",
                List.of())));
  }

  private static LabelInfo label(int value) {
    return label(approval(1001, value));
  }

  private static LabelInfo label(ApprovalInfo... approvals) {
    LabelInfo label = new LabelInfo();
    label.all = List.of(approvals);
    return label;
  }

  private static ApprovalInfo approval(int accountId, int value) {
    ApprovalInfo approval = new ApprovalInfo(accountId);
    approval.value = value;
    return approval;
  }
}
