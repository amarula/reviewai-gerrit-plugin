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
import static org.mockito.Mockito.mock;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.List;
import org.junit.Test;

public class ClientCommandCleanerTest {
  private static final String FILE_PATH =
      "common/src/commonMain/kotlin/com/amarula/travelsmart/common/ui/screens/ViewExpenseScreen.kt";

  private final ClientCommandCleaner cleaner = new ClientCommandCleaner(mock(Configuration.class));

  @Test
  public void pathsAreNotRemovedFromComment() {
    for (String comment :
        List.of(
            "Defined in " + FILE_PATH,
            "Defined in /common/src/main/ViewExpenseScreen.kt",
            "The file /usr/lib/foo.kt is used",
            "Defined in src/message/Handler.kt",
            "Defined in src/directives/Handler.kt",
            "and/or is fine")) {
      assertEquals(comment, cleaner.removeCommands(comment));
    }
  }

  @Test
  public void commandsAreRemovedFromComment() {
    assertEquals("", cleaner.removeCommands("/review --scope=patchset"));
    assertEquals("", cleaner.removeCommands("/forget_thread"));
  }

  @Test
  public void messageCommandKeepsItsMessage() {
    assertEquals(
        " Defined in " + FILE_PATH, cleaner.removeCommands("/message Defined in " + FILE_PATH));
  }
}
