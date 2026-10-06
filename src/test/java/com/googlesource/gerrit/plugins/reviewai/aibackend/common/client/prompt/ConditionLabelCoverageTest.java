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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import java.util.List;
import org.junit.Test;

public class ConditionLabelCoverageTest {
  @Test
  public void derivesScopesFromDescriptionKeywords() {
    assertEquals(
        "compilation, automated tests", describe("automated tests have run and the code compiles"));
    assertEquals("compilation", describe("the patch set builds and the binary is produced"));
    assertEquals("static analysis", describe("the SonarQube quality gate passed"));
    assertEquals("security scanning", describe("no CVE was reported by the SAST scan"));
    assertEquals("documentation", describe("the Javadoc is up to date"));
  }

  @Test
  public void derivesNoScopeWhenDescriptionNamesNone() {
    assertEquals("", describe("CI verification"));
    assertEquals("", describe(null));
    assertEquals(
        "",
        ConditionLabelCoverage.describe(
            new GerritConditionLabel(List.of(), List.of(), "the code compiles"),
            "the code compiles"));
  }

  @Test
  public void derivesNoScopeWithoutPositiveVote() {
    assertEquals(
        "",
        ConditionLabelCoverage.describe(
            new GerritConditionLabel(List.of((short) 0), List.of((short) 0, (short) 1), null),
            "the code compiles"));
    assertEquals(
        "",
        ConditionLabelCoverage.describe(
            new GerritConditionLabel(List.of((short) -1), List.of((short) -1, (short) 1), null),
            "the code compiles"));
  }

  @Test
  public void conflictingVotesAreNotConclusive() {
    assertFalse(
        ConditionLabelCoverage.isConclusivePositive(
            new GerritConditionLabel(
                List.of((short) -1, (short) 1), List.of(), "the code compiles")));
    assertFalse(
        ConditionLabelCoverage.isConclusivePositive(
            new GerritConditionLabel(
                List.of((short) 0, (short) -1), List.of(), "the code compiles")));
    assertTrue(
        ConditionLabelCoverage.isConclusivePositive(
            new GerritConditionLabel(List.of((short) 1), List.of(), "the code compiles")));
    assertTrue(
        ConditionLabelCoverage.isConclusivePositive(
            new GerritConditionLabel(
                List.of((short) 0, (short) 2), List.of(), "the code compiles")));
  }

  private static String describe(String description) {
    return ConditionLabelCoverage.describe(
        new GerritConditionLabel(List.of((short) 1), List.of((short) 0, (short) 1), null),
        description);
  }
}
