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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.utils.PluginBuild;
import java.util.List;
import org.junit.Test;
import org.mockito.MockedStatic;

public class ClientCommandParserTest {
  @Test
  public void invalidForgetThreadCommandDoesNotClearConcerns() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    Localizer localizer = localizer();
    ReviewConcernPublisher reviewConcernPublisher = mock(ReviewConcernPublisher.class);
    ClientCommandParser parser =
        new ClientCommandParser(
            mock(Configuration.class),
            changeSetData,
            mock(GerritChange.class),
            null,
            null,
            localizer,
            null,
            null,
            false,
            reviewConcernPublisher,
            new DisabledClientCommandExtension());

    assertTrue(parser.parseCommands("/forget_thread --unknown=true"));

    verifyNoInteractions(reviewConcernPublisher);
  }

  @Test
  public void productionBuildRejectsDevOnlyCommandsWithDevBuildRequiredMessage() {
    try (MockedStatic<PluginBuild> pluginBuild = mockStatic(PluginBuild.class)) {
      pluginBuild.when(PluginBuild::isProductionBuild).thenReturn(true);
      for (String command : List.of("/show", "/review --debug", "/configure", "/directives")) {
        ChangeSetData changeSetData = new ChangeSetData(1);
        Localizer localizer = localizer();
        ClientCommandParser parser =
            new ClientCommandParser(
                mock(Configuration.class),
                changeSetData,
                mock(GerritChange.class),
                null,
                null,
                localizer,
                null,
                null);

        assertTrue(parser.parseCommands(command));

        assertEquals(
            SystemMessageFormatter.getLocalizedWarningMessage(
                localizer, "message.command.dev.build.required"),
            changeSetData.getReviewSystemMessage());
      }
    }
  }

  private static Localizer localizer() {
    Localizer localizer = mock(Localizer.class);
    when(localizer.getText("plugin.message.prefix")).thenReturn("ReviewAI");
    when(localizer.getText("plugin.message.label")).thenReturn("Message");
    when(localizer.getText("plugin.warning.label")).thenReturn("**WARNING**");
    when(localizer.getText("plugin.error.label")).thenReturn("**ERROR**");
    when(localizer.getText("message.command.dev.build.required"))
        .thenReturn("Unable to execute command: the -dev build is required");
    when(localizer.getText("message.command.option.unknown"))
        .thenReturn("Unknown command option: %s %s");
    return localizer;
  }
}
