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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory;

import static org.junit.Assert.assertEquals;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewAssistantStage;
import org.junit.Test;

public class LangChainMemoryIdTest {

  @Test
  public void commentEventsUseRequestsScope() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    GerritChange change = new GerritChange("change~1");
    change.setIsCommentEvent(true);

    LangChainMemoryId memoryId = LangChainMemoryId.from(changeSetData, change);

    assertEquals("requests", memoryId.getScope());
  }

  @Test
  public void routedCommentEventsUseReviewStageScope() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    changeSetData.setForcedStagedReview(true);
    changeSetData.setReviewAssistantStage(ReviewAssistantStage.REVIEW_COMMIT_MESSAGE);
    GerritChange change = new GerritChange("change~1");
    change.setIsCommentEvent(true);

    LangChainMemoryId memoryId = LangChainMemoryId.from(changeSetData, change);

    assertEquals("review_commit_message", memoryId.getScope());
  }

  @Test
  public void reviewEventsKeepReviewStageScope() {
    ChangeSetData changeSetData = new ChangeSetData(1, -1, 1);
    GerritChange change = new GerritChange("change~1");

    LangChainMemoryId memoryId = LangChainMemoryId.from(changeSetData, change);

    assertEquals("review_code", memoryId.getScope());
  }
}
