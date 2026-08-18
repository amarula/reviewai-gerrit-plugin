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

package com.googlesource.gerrit.plugins.reviewai.review.topic;

import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.server.data.PatchSetAttribute;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

public class TopicPatchSetReviewTest {
  private static final Path PATCH_SET_RESOURCE =
      TestResourceLoader.getTestResourcePath().resolve("__files/langchain/suggestOriginalPatchSet.txt");

  @Test
  public void topicMergerPrefixesPatchFilenamesAndAddsOriginMetadata() throws Exception {
    TopicPatchSetReviewMerger merger = new TopicPatchSetReviewMerger();
    GerritChange change = change("project~branch~change1", 3);
    TopicReviewPatchSet patchSet =
        merger.patchSet(change, 1, Files.readString(PATCH_SET_RESOURCE));

    String mergedPatchSet = merger.buildMergedPatchSet(List.of(patchSet));

    assertTrue(mergedPatchSet.contains("ReviewAI origin: reviewai-topic-change-1/"));
    assertTrue(mergedPatchSet.contains("Gerrit change: project~branch~change1"));
    assertTrue(mergedPatchSet.contains("Patch set: 3"));
    assertTrue(mergedPatchSet.contains("diff --git a/reviewai-topic-change-1/a.py"));
    assertTrue(mergedPatchSet.contains("--- a/reviewai-topic-change-1/a.py"));
    assertTrue(mergedPatchSet.contains("+++ b/reviewai-topic-change-1/a.py"));
  }

  @Test
  public void topicReplyMapperFiltersOtherPatchSetsAndStripsOwnPrefix() {
    TopicReviewReplyMapper mapper = new TopicReviewReplyMapper();
    AiReplyItem ownReply =
        AiReplyItem.builder()
            .concernId("raw-concern-1")
            .filename("reviewai-topic-change-1/src/Test.java")
            .repeated(true)
            .repetitionReplyId("raw-previous-r1")
            .build();
    AiReplyItem otherReply =
        AiReplyItem.builder().filename("reviewai-topic-change-2/src/Test.java").build();
    AiReplyItem messageReply = AiReplyItem.builder().build();

    Optional<AiReplyItem> mappedReply = mapper.replyForChange(ownReply, "reviewai-topic-change-1/");

    assertTrue(mappedReply.isPresent());
    assertEquals("src/Test.java", mappedReply.get().getFilename());
    assertEquals("raw-concern-1", mappedReply.get().getConcernId());
    assertEquals("reviewai-topic-change-1/src/Test.java", ownReply.getFilename());
    assertTrue(mappedReply.get().isRepeated());
    assertEquals("raw-previous-r1", mappedReply.get().getRepetitionReplyId());
    assertFalse(mapper.replyForChange(otherReply, "reviewai-topic-change-1/").isPresent());
    assertTrue(mapper.replyForChange(messageReply, "reviewai-topic-change-1/").isPresent());
  }

  private static GerritChange change(String fullChangeId, int patchSetNumber) {
    GerritChange change = mock(GerritChange.class);
    PatchSetAttribute patchSet = new PatchSetAttribute();
    patchSet.number = patchSetNumber;
    when(change.getFullChangeId()).thenReturn(fullChangeId);
    when(change.getPatchSetAttribute()).thenReturn(Optional.of(patchSet));
    return change;
  }
}
