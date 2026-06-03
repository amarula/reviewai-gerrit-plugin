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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.data.PatchSetAttribute;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory.PluginChatMemoryStore;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.util.Map;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class ClientCommandExecutorTest {
  @Mock private Configuration config;
  @Mock private GerritChange change;
  @Mock private PluginDataHandlerProvider pluginDataHandlerProvider;
  @Mock private PluginDataHandler changeDataHandler;
  @Mock private Localizer localizer;
  @Mock private PluginChatMemoryStore chatMemoryStore;

  @Test
  public void forgetThreadClearsLangChainMemoryForCurrentChangeAndPatchSet() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    when(pluginDataHandlerProvider.getChangeScope()).thenReturn(changeDataHandler);
    when(changeDataHandler.getValue("conversationId")).thenReturn("conv-1");
    when(change.getFullChangeId()).thenReturn("change~1");
    PatchSetAttribute patchSetAttribute = new PatchSetAttribute();
    patchSetAttribute.number = 1;
    when(change.getPatchSetAttribute()).thenReturn(Optional.of(patchSetAttribute));
    when(localizer.getText("message.command.thread.forget")).thenReturn("forgot");

    ClientCommandExecutor executor =
        new ClientCommandExecutor(
            config,
            changeSetData,
            change,
            null,
            pluginDataHandlerProvider,
            localizer,
            null,
            chatMemoryStore);

    executor.executeCommand(
        ClientCommandBase.CommandSet.FORGET_THREAD, Map.of(), Map.of(), "");

    verify(chatMemoryStore).deleteMessagesForChangeSet("change~1", 1);
    assertEquals("forgot", changeSetData.getReviewSystemMessage());
  }
}
