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

package com.googlesource.gerrit.plugins.reviewai.web;

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.reflect.TypeToken;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class AiReviewThreadsTest {
  private static final int AI_ACCOUNT_ID = 7;

  private List<GerritComment> comments;

  @Before
  public void setUp() throws IOException {
    String json =
        Files.readString(
            TestResourceLoader.getTestResourcePath()
                .resolve("__files/gerritCommentThreads.json"));
    comments = getGson().fromJson(json, new TypeToken<List<GerritComment>>() {}.getType());
    setAuthor("ai-concern", AI_ACCOUNT_ID, "ReviewAI");
    setAuthor("ai-ack", AI_ACCOUNT_ID, "ReviewAI");
    setAuthor("review-command", 42, "Alice");
    setAuthor("user-reply", 42, "Alice");
    setAuthor("sibling-reply", 43, "Bob");
  }

  @Test
  public void groupsEveryThreadContainingAnAiComment() {
    AiReviewThreads.Output output = AiReviewThreads.buildOutput(comments, AI_ACCOUNT_ID);

    assertEquals(8, output.totalComments);
    assertEquals(2, output.aiComments);
    assertEquals(1, output.totalThreads);

    AiReviewThreads.ThreadInfo thread = output.threads.get(0);
    assertEquals("review-command", thread.rootId);
    assertFalse(thread.rootIsAi);
    assertEquals(5, thread.size);
    assertTrue(thread.hasUserReply);
    assertEquals(
        List.of("review-command", "ai-concern", "user-reply", "ai-ack", "sibling-reply"),
        thread.comments.stream().map(comment -> comment.id).toList());
  }

  private void setAuthor(String id, int accountId, String name) {
    GerritComment.Author author = new GerritComment.Author();
    author.setAccountId(accountId);
    author.setName(name);
    comments.stream()
        .filter(comment -> id.equals(comment.getId()))
        .findFirst()
        .orElseThrow()
        .setAuthor(author);
  }
}
