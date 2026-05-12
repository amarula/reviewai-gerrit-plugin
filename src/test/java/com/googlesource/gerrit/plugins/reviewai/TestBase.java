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

package com.googlesource.gerrit.plugins.reviewai;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import java.nio.file.Path;

import static org.mockito.Mockito.lenient;

public class TestBase {
  protected static final Project.NameKey PROJECT_NAME = Project.NameKey.parse("myProject");
  protected static final Change.Key CHANGE_ID = Change.Key.parse("myChangeId");
  protected static final BranchNameKey BRANCH_NAME =
      BranchNameKey.create(PROJECT_NAME, "myBranchName");

  @ClassRule public static final H2TcpServerRule h2TcpServer = new H2TcpServerRule();
  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Mock protected Path mockPluginDataPath;

  protected Path realPluginDataPath;

  protected void setupPluginData() {
    realPluginDataPath = tempFolder.getRoot().toPath().resolve("global.data");
    Path realProjectDataPath = tempFolder.getRoot().toPath().resolve(PROJECT_NAME + ".data");

    // Mock the PluginData annotation project behavior
    lenient()
        .when(mockPluginDataPath.resolve(PROJECT_NAME + ".data"))
        .thenReturn(realProjectDataPath);
  }

  protected GerritChange getGerritChange() {
    return new GerritChange(TestBase.PROJECT_NAME, TestBase.BRANCH_NAME, TestBase.CHANGE_ID);
  }
}
