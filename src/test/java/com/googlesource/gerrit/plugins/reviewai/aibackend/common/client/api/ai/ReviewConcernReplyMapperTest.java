/*
 * Copyright (c) 2026. Amarula Solutions
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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai;

import static org.junit.Assert.assertEquals;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import org.junit.Test;

public class ReviewConcernReplyMapperTest {
  private static final ConcernReviewerId REVIEWER =
      new ConcernReviewerId(ConcernReviewerId.Kind.SCOPED_AGENT, "PATCHSET");

  @Test
  public void mapsReplyToCanonicalConcernAndBack() {
    AiReplyItem original =
        AiReplyItem.builder()
            .reply("A null value is dereferenced.")
            .score(-1.0)
            .relevance(0.9)
            .filename("src/Example.java")
            .lineNumber(42)
            .codeSnippet("value.run();")
            .build();

    ReviewConcern concern = ReviewConcernReplyMapper.fromReply(original, REVIEWER, "concern-1");
    AiReplyItem restored = ReviewConcernReplyMapper.toReply(concern);

    assertEquals("concern-1", concern.getId());
    assertEquals(ConcernStatus.PRESENT, concern.getStatus());
    assertEquals("concern-1", restored.getConcernId());
    assertEquals(original.getReply(), restored.getReply());
    assertEquals(original.getFilename(), restored.getFilename());
    assertEquals(original.getLineNumber(), restored.getLineNumber());
    assertEquals(original.getCodeSnippet(), restored.getCodeSnippet());
  }

  @Test
  public void preservesExistingConcernId() {
    AiReplyItem reply =
        AiReplyItem.builder().concernId("existing-id").reply("Existing concern").build();

    ReviewConcern concern = ReviewConcernReplyMapper.fromReply(reply, REVIEWER, "generated-id");

    assertEquals("existing-id", concern.getId());
  }
}
