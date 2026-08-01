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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritConditionLabel;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Test;

public class AiPromptConditionLabelFormatterTest {
  private static final String VERIFIED_DEFAULT_DESCRIPTION_RESOURCE =
      "__files/prompts/verifiedConditionLabelDefaultDescription.txt";

  @Test
  public void formatsValuesDescriptionsAndMissingVotes() {
    Localizer localizer = localizer();

    assertEquals(
        "- Code-Review: no vote\n- Verified: -1, +1\n  Description: CI verification",
        AiPromptConditionLabelFormatter.format(
            Map.of(
                "Verified",
                new GerritConditionLabel(
                    List.of((short) -1, (short) 1), "CI verification"),
                "Code-Review",
                new GerritConditionLabel(List.of(), null)),
            localizer::getText));
  }

  @Test
  public void usesDefaultDescriptionWhenVerifiedDescriptionIsMissing() throws Exception {
    Localizer localizer = localizer();
    String expected =
        Files.readString(
                TestResourceLoader.getTestResourcePath()
                    .resolve(VERIFIED_DEFAULT_DESCRIPTION_RESOURCE))
            .strip();

    assertEquals(
        expected,
        AiPromptConditionLabelFormatter.format(
            Map.of(
                "Verified",
                new GerritConditionLabel(List.of((short) 0, (short) 1), null)),
            localizer::getText));
    assertEquals(
        expected,
        AiPromptConditionLabelFormatter.format(
            Map.of(
                "Verified",
                new GerritConditionLabel(List.of((short) 0, (short) 1), "  ")),
            localizer::getText));
  }

  private static Localizer localizer() {
    Configuration config = mock(Configuration.class);
    when(config.getLocaleDefault()).thenReturn(Locale.ROOT);
    return new Localizer(config);
  }
}
