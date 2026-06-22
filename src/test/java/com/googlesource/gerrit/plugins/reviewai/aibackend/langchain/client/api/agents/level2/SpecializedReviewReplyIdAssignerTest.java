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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import static org.junit.Assert.assertEquals;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import java.util.List;
import org.junit.Test;

public class SpecializedReviewReplyIdAssignerTest {
  @Test
  public void assignsUniqueIdsAcrossAgentsWithinPatchsetNamespace() {
    List<SpecializedReviewAgentReplies> replies =
        List.of(
            SpecializedReviewAgentReplies.from(
                "CORRECTNESS",
                List.of(
                    AiReplyItem.builder().reply("First").build(),
                    AiReplyItem.builder().reply("Second").build())),
            SpecializedReviewAgentReplies.from(
                "SECURITY", List.of(AiReplyItem.builder().reply("Third").build())));

    SpecializedReviewReplyIdAssigner.assign(7, replies);

    assertEquals(Integer.valueOf(700_000), replies.get(0).getReplies().get(0).getId());
    assertEquals(Integer.valueOf(700_001), replies.get(0).getReplies().get(1).getId());
    assertEquals(Integer.valueOf(700_002), replies.get(1).getReplies().get(0).getId());
  }
}
