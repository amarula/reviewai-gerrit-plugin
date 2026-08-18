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

package com.googlesource.gerrit.plugins.reviewai.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class ReviewFeedbackStoreTest extends TestBase {
  private ReviewFeedbackStore store;

  @Before
  public void setUp() {
    store = new ReviewFeedbackStore(getTestReviewAiDb(), "change-1");
  }

  @Test
  public void claimsEachCommentOnlyOnceIncludingAfterReplay() {
    store.enqueue(List.of("comment-1", "comment-1", "comment-2"));

    ReviewFeedbackStore.Claim claim = store.claimPending();

    assertEquals(List.of("comment-1", "comment-2"), claim.commentIds());
    assertTrue(store.claimPending().isEmpty());

    store.complete(claim, new ReviewFeedbackMemory());
    store.enqueue(List.of("comment-1", "comment-2"));

    assertTrue(store.claimPending().isEmpty());
  }

  @Test
  public void releasedClaimCanBeRetried() {
    store.enqueue(List.of("comment-1"));
    ReviewFeedbackStore.Claim claim = store.claimPending();

    store.release(claim);

    assertEquals(List.of("comment-1"), store.claimPending().commentIds());
  }

  @Test
  public void completionStoresMemoryAndRetiresClaim() {
    store.enqueue(List.of("comment-1"));
    ReviewFeedbackStore.Claim claim = store.claimPending();
    ReviewFeedbackMemory memory = new ReviewFeedbackMemory();

    store.complete(claim, memory);

    assertEquals(memory, store.loadMemory().orElseThrow());
    assertTrue(store.claimPending().isEmpty());
  }

  @Test
  public void staleClaimDoesNotReplaceMemory() {
    store.enqueue(List.of("comment-1"));
    ReviewFeedbackStore.Claim claim = store.claimPending();
    ReviewFeedbackStore.Claim staleClaim =
        new ReviewFeedbackStore.Claim("stale-token", claim.commentIds());

    try {
      store.complete(staleClaim, new ReviewFeedbackMemory());
      fail("Expected the stale claim to be rejected");
    } catch (IllegalStateException expected) {
      // Expected.
    }

    assertTrue(store.loadMemory().isEmpty());
    store.release(claim);
  }

  @Test
  public void forgetClearsMemoryAndRetiresPendingComments() {
    store.enqueue(List.of("comment-1"));
    ReviewFeedbackStore.Claim firstClaim = store.claimPending();
    store.complete(firstClaim, new ReviewFeedbackMemory());
    store.enqueue(List.of("comment-2"));

    store.forget();

    assertFalse(store.loadMemory().isPresent());
    assertTrue(store.claimPending().isEmpty());
    store.enqueue(List.of("comment-1", "comment-2"));
    assertTrue(store.claimPending().isEmpty());
  }
}
