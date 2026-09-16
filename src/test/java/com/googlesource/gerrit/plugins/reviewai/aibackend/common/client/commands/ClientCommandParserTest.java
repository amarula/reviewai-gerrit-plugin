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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewConcernPublisher;
import com.googlesource.gerrit.plugins.reviewai.data.ReviewFeedbackPublisher;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;
import com.googlesource.gerrit.plugins.reviewai.permissions.AiRole;
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
    ReviewFeedbackPublisher reviewFeedbackPublisher = mock(ReviewFeedbackPublisher.class);
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
            AiRole.USER,
            reviewConcernPublisher,
            reviewFeedbackPublisher,
            new DisabledClientCommandExtension());

    assertTrue(parser.parseCommands("/forget_thread --unknown=true"));

    verifyNoInteractions(reviewConcernPublisher);
    verifyNoInteractions(reviewFeedbackPublisher);
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

  @Test
  public void forgetThreadRequiresModeratorPrivileges() {
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

    assertTrue(parser.parseCommands("/forget_thread", false));

    assertFalse(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.FORGET_THREAD));
    assertEquals(
        SystemMessageFormatter.getPrefixedSystemMessage(
            localizer, localizer.getText("message.command.moderator.required")),
        changeSetData.getReviewSystemMessage());
  }

  @Test
  public void deniedCommandAbortsEntireCommandChainInDevelopmentBuild() {
    try (MockedStatic<PluginBuild> pluginBuild = mockStatic(PluginBuild.class)) {
      pluginBuild.when(PluginBuild::isProductionBuild).thenReturn(false);
      for (DeniedCommandChain testCase :
          List.of(
              new DeniedCommandChain(AiRole.USER, "/forget_thread /review"),
              new DeniedCommandChain(AiRole.USER, "/review /forget_thread"),
              new DeniedCommandChain(AiRole.MODERATOR, "/show --config /review"),
              new DeniedCommandChain(AiRole.MODERATOR, "/review /show --config"),
              new DeniedCommandChain(AiRole.MODERATOR, "/review --debug /suggest"),
              new DeniedCommandChain(AiRole.MODERATOR, "/suggest /review --debug"))) {
        ChangeSetData changeSetData = new ChangeSetData(1);
        ReviewConcernPublisher reviewConcernPublisher = mock(ReviewConcernPublisher.class);
        ReviewFeedbackPublisher reviewFeedbackPublisher = mock(ReviewFeedbackPublisher.class);
        ClientCommandParser parser =
            new ClientCommandParser(
                mock(Configuration.class),
                changeSetData,
                mock(GerritChange.class),
                null,
                null,
                localizer(),
                null,
                null,
                testCase.role(),
                reviewConcernPublisher,
                reviewFeedbackPublisher,
                new DisabledClientCommandExtension());

        assertTrue(parser.parseCommands(testCase.commands()));

        assertFalse(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.FORGET_THREAD));
        assertFalse(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.REVIEW));
        assertFalse(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.SUGGEST));
        assertFalse(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.SHOW));
        assertFalse(changeSetData.getForcedReview());
        verifyNoInteractions(reviewConcernPublisher);
        verifyNoInteractions(reviewFeedbackPublisher);
      }
    }
  }

  @Test
  public void administratorCanParseProtectedCommandChainInDevelopmentBuild() {
    try (MockedStatic<PluginBuild> pluginBuild = mockStatic(PluginBuild.class)) {
      pluginBuild.when(PluginBuild::isProductionBuild).thenReturn(false);
      ChangeSetData changeSetData = new ChangeSetData(1);
      ClientCommandParser parser = parserForRole(changeSetData, AiRole.ADMINISTRATOR);

      assertTrue(parser.parseCommands("/forget_thread /show --config /review --debug", false));

      assertTrue(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.FORGET_THREAD));
      assertTrue(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.SHOW));
      assertTrue(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.REVIEW));
    }
  }

  @Test
  public void helpDoesNotPreflightCommandsThatFollowIt() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    ClientCommandParser parser = parserForRole(changeSetData, AiRole.MODERATOR);

    assertTrue(parser.parseCommands("/help /show --config", false));

    assertTrue(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.HELP));
    assertFalse(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.SHOW));
  }

  @Test
  public void messageBypassesDeniedCommandPreflight() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    ClientCommandParser parser = parserForRole(changeSetData, AiRole.USER);

    assertFalse(parser.parseCommands("/message /forget_thread", false));

    assertFalse(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.FORGET_THREAD));
    assertNull(changeSetData.getReviewSystemMessage());
  }

  @Test
  public void forgetThreadIsParsedForModerator() {
    ChangeSetData changeSetData = new ChangeSetData(1);
    ClientCommandParser parser =
        new ClientCommandParser(
            mock(Configuration.class),
            changeSetData,
            mock(GerritChange.class),
            null,
            null,
            localizer(),
            null,
            null,
            AiRole.MODERATOR,
            new DisabledClientCommandExtension());

    assertTrue(parser.parseCommands("/forget_thread", false));

    assertTrue(changeSetData.hasParsedCommand(ClientCommandBase.CommandSet.FORGET_THREAD));
  }

  private static ClientCommandParser parserForRole(ChangeSetData changeSetData, AiRole role) {
    return new ClientCommandParser(
        mock(Configuration.class),
        changeSetData,
        mock(GerritChange.class),
        null,
        null,
        localizer(),
        null,
        null,
        role,
        new DisabledClientCommandExtension());
  }

  private record DeniedCommandChain(AiRole role, String commands) {}

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
    when(localizer.getText("message.command.moderator.required"))
        .thenReturn("Unable to execute command: Moderator privileges are required");
    return localizer;
  }
}
