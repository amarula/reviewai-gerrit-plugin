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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import static org.junit.Assert.assertEquals;

import com.google.gerrit.extensions.common.ChangeInfo;
import org.junit.Test;

public class GerritClientDetailTest {
  @Test
  public void toGerritChangeCopiesNumericChangeNumber() {
    ChangeInfo changeInfo = new ChangeInfo();
    changeInfo.project = "myProject";
    changeInfo.branch = "myBranchName";
    changeInfo.changeId = "myChangeId";
    changeInfo._number = 15438;
    changeInfo.currentRevision = "revision-3";

    GerritChange change = GerritClientDetail.toGerritChange(changeInfo);

    assertEquals(Integer.valueOf(15438), change.getChangeNumber().orElseThrow());
    assertEquals("revision-3", change.getPatchSetRevision());
  }
}
