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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static java.util.stream.Collectors.toList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.gson.reflect.TypeToken;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class GerritCommentThreadIndexTest {
  private List<GerritComment> comments;
  private GerritCommentThreadIndex index;

  @Before
  public void setUp() throws IOException {
    String json =
        Files.readString(
            TestResourceLoader.getTestResourcePath()
                .resolve("__files/gerritCommentThreads.json"));
    comments = getGson().fromJson(json, new TypeToken<List<GerritComment>>() {}.getType());
    index = new GerritCommentThreadIndex(comments);
  }

  @Test
  public void lineageFollowsExactBranchBackToUserCommand() {
    GerritComment acknowledgement = comment("ai-ack");

    assertEquals(
        List.of("review-command", "ai-concern", "user-reply", "ai-ack"),
        ids(index.lineage(acknowledgement)));
    assertEquals("review-command", index.rootOf(acknowledgement).orElseThrow().getId());
    assertEquals(
        "ai-concern",
        index
            .nearestAncestor(acknowledgement, candidate -> candidate.getId().startsWith("ai-"))
            .orElseThrow()
            .getId());
  }

  @Test
  public void childrenAreOrderedWithoutCrossingSiblingBranches() {
    assertEquals(
        List.of("user-reply", "sibling-reply"),
        ids(index.childrenOf(comment("ai-concern"))));
    assertEquals(
        List.of("review-command", "ai-concern", "sibling-reply"),
        ids(index.lineage(comment("sibling-reply"))));
  }

  @Test
  public void missingParentMakesCommentTheKnownRoot() {
    GerritComment orphan = comment("orphan");

    assertEquals(List.of("orphan"), ids(index.lineage(orphan)));
    assertEquals("orphan", index.rootOf(orphan).orElseThrow().getId());
  }

  @Test
  public void cyclesTerminateWithoutClaimingARoot() {
    GerritComment cycleA = comment("cycle-a");

    assertEquals(List.of("cycle-b", "cycle-a"), ids(index.lineage(cycleA)));
    assertFalse(index.rootOf(cycleA).isPresent());
    assertFalse(index.nearestAncestor(cycleA, candidate -> false).isPresent());
  }

  private GerritComment comment(String id) {
    return comments.stream()
        .filter(comment -> id.equals(comment.getId()))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> ids(List<GerritComment> comments) {
    return comments.stream().map(GerritComment::getId).collect(toList());
  }
}
