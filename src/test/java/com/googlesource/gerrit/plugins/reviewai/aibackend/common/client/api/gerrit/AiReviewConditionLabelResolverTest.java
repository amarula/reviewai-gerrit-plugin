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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import static org.junit.Assert.assertEquals;

import com.google.gerrit.extensions.common.ApprovalInfo;
import com.google.gerrit.extensions.common.LabelInfo;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiReviewConditionLabelResolverTest {
  private static final String CHANGE_ID = "project~main~I123";

  @Test
  public void resolvesEveryLabelReferencedByCondition() {
    AiReviewConditionLabelResolver resolver = new AiReviewConditionLabelResolver();
    resolver.cacheCurrentLabels(
        CHANGE_ID,
        Map.of(
            "Verified",
            label("CI verification", approval(1001, 1)),
            "Code-Review",
            label("Code quality", approval(1001, 2))));

    assertEquals(
        Map.of(
            "Verified",
            new GerritConditionLabel(List.of((short) 1), "CI verification"),
            "Code-Review",
            new GerritConditionLabel(List.of((short) 2), "Code quality"),
            "Build-Check",
            new GerritConditionLabel(List.of(), null)),
        resolver.resolve(
            CHANGE_ID,
            "label:Verified=+1 OR (label:\"Code-Review>=2\" AND -label:Build-Check-1)"));
  }

  @Test
  public void valuesAreDistinctAndDoNotContainAccounts() {
    AiReviewConditionLabelResolver resolver = new AiReviewConditionLabelResolver();
    resolver.cacheCurrentLabels(
        CHANGE_ID,
        Map.of(
            "Verified",
            label(
                "CI verification",
                approval(1001, -1), approval(1002, 1), approval(1003, 1))));

    assertEquals(
        Map.of(
            "Verified",
            new GerritConditionLabel(List.of((short) -1, (short) 1), "CI verification")),
        resolver.resolve(CHANGE_ID, "label:Verified=+1"));
  }

  private static LabelInfo label(int value) {
    return label(null, approval(1001, value));
  }

  private static LabelInfo label(String description, ApprovalInfo... approvals) {
    LabelInfo label = new LabelInfo();
    label.all = List.of(approvals);
    label.description = description;
    return label;
  }

  private static ApprovalInfo approval(int accountId, int value) {
    ApprovalInfo approval = new ApprovalInfo(accountId);
    approval.value = value;
    return approval;
  }
}
