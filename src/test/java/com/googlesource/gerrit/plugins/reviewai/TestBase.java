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
import com.googlesource.gerrit.plugins.reviewai.data.ReviewAiDb;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;

import static org.mockito.Mockito.lenient;

public class TestBase {
  protected static final Project.NameKey PROJECT_NAME = Project.NameKey.parse("myProject");
  protected static final Change.Key CHANGE_ID = Change.Key.parse("myChangeId");
  protected static final BranchNameKey BRANCH_NAME =
      BranchNameKey.create(PROJECT_NAME, "myBranchName");

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  @Mock protected Path mockPluginDataPath;

  protected Path realPluginDataPath;
  private ReviewAiDb testReviewAiDb;

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

  protected ReviewAiDb getTestReviewAiDb() {
    if (testReviewAiDb == null) {
      Path pluginDataDir = tempFolder.getRoot().toPath();
      try {
        testReviewAiDb = new ReviewAiDb(pluginDataDir, buildEmbeddedTestJdbcUrl(pluginDataDir));
      } catch (IOException e) {
        throw new RuntimeException("Failed to initialize test ReviewAI DB", e);
      }
    }
    return testReviewAiDb;
  }

  protected static String buildEmbeddedTestJdbcUrl(Path pluginDataDir) {
    return "jdbc:h2:"
        + pluginDataDir.resolve("reviewai").toAbsolutePath().normalize()
        + ";AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1";
  }
}
