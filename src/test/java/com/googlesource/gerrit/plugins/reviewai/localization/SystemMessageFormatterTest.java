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

package com.googlesource.gerrit.plugins.reviewai.localization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;

public class SystemMessageFormatterTest {
  @Test
  public void prefixesSystemMessageOnlyOnce() {
    Localizer localizer = localizer();

    assertEquals(
        "SYSTEM MESSAGE: No update",
        SystemMessageFormatter.getPrefixedSystemMessage(localizer, "No update"));
    assertEquals(
        "SYSTEM MESSAGE: No update",
        SystemMessageFormatter.getPrefixedSystemMessage(localizer, "SYSTEM MESSAGE: No update"));
    assertNull(SystemMessageFormatter.getPrefixedSystemMessage(localizer, null));
  }

  @Test
  public void detectsSystemMessages() {
    Localizer localizer = localizer();

    assertTrue(SystemMessageFormatter.isSystemMessage(localizer, "SYSTEM MESSAGE: No update"));
    assertTrue(SystemMessageFormatter.isSystemMessage(localizer, "  SYSTEM MESSAGE: No update"));
    assertFalse(SystemMessageFormatter.isSystemMessage(localizer, "No update"));
    assertFalse(SystemMessageFormatter.isSystemMessage(localizer, null));
  }

  @Test
  public void formatsLocalizedWarningMessage() {
    Localizer localizer = localizer();

    assertEquals(
        "**WARNING:** Unrecognized value for configuration setting `aiProvider`. "
            + "Default value will be applied.",
        SystemMessageFormatter.getLocalizedWarningMessage(
            localizer, "message.config.unknown.enum.warning", "aiProvider"));
  }

  @Test
  public void formatsLocalizedErrorMessage() {
    Localizer localizer = localizer();

    assertEquals(
        "**ERROR:** Unable to connect to AI server",
        SystemMessageFormatter.getLocalizedErrorMessage(
            localizer, "message.openai.connection.error"));
  }

  @Test
  public void appendsConfigurationWarningMessages() {
    Localizer localizer = localizer();
    Configuration config = mock(Configuration.class);
    when(config.getUnknownEnumSettings()).thenReturn(Set.of("aiProvider"));
    List<String> messages = new ArrayList<>();

    SystemMessageFormatter.appendConfigurationWarningMessages(config, localizer, messages);

    assertEquals(
        List.of(
            "**WARNING:** Unrecognized value for configuration setting `aiProvider`. "
                + "Default value will be applied."),
        messages);
  }

  private Localizer localizer() {
    Localizer localizer = mock(Localizer.class);
    when(localizer.getText("system.message.prefix")).thenReturn("SYSTEM MESSAGE:");
    when(localizer.getText("warning.message.prefix")).thenReturn("**WARNING:**");
    when(localizer.getText("error.message.prefix")).thenReturn("**ERROR:**");
    when(localizer.getText("message.config.unknown.enum.warning"))
        .thenReturn(
            "Unrecognized value for configuration setting `%s`. Default value will be applied.");
    when(localizer.getText("message.openai.connection.error"))
        .thenReturn("Unable to connect to AI server");
    return localizer;
  }
}
