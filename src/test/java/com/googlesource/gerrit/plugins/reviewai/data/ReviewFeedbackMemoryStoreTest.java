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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewFeedbackMemory;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class ReviewFeedbackMemoryStoreTest extends TestBase {
  private static final String MEMORY_RESOURCE = "__files/feedback/reviewFeedbackMemory.json";
  private static final String DISABLED_COMMIT_MESSAGE_MEMORY_RESOURCE =
      "__files/feedback/reviewFeedbackMemoryDisabledCommitMessage.json";
  private static final String DISABLED_TESTABILITY_MEMORY_RESOURCE =
      "__files/feedback/reviewFeedbackMemoryDisabledTestability.json";

  private ReviewFeedbackMemoryStore store;

  @Before
  public void setUp() {
    store = new ReviewFeedbackMemoryStore(getTestReviewAiDb(), "change-1");
  }

  @Test
  public void storesGenericAndConcernFeedback() throws Exception {
    ReviewFeedbackMemory memory = loadMemory();
    assertTrue(memory.getDisabledReviewScopes().isEmpty());

    store.save(memory);

    assertEquals(memory, store.load().orElseThrow());
  }

  @Test
  public void storesDisabledReviewScopes() throws Exception {
    ReviewFeedbackMemory memory = loadMemory(DISABLED_COMMIT_MESSAGE_MEMORY_RESOURCE);

    store.save(memory);

    assertEquals(memory, store.load().orElseThrow());
  }

  @Test
  public void storesDisabledSpecializedAgents() throws Exception {
    ReviewFeedbackMemory memory = loadMemory(DISABLED_TESTABILITY_MEMORY_RESOURCE);

    store.save(memory);

    assertEquals(memory, store.load().orElseThrow());
  }

  @Test
  public void saveReplacesExistingMemory() throws Exception {
    ReviewFeedbackMemory memory = loadMemory();
    store.save(memory);
    ReviewFeedbackMemory replacement = new ReviewFeedbackMemory();
    replacement.setConcernFeedback(
        Map.of("concern-2", memory.getConcernFeedback().get("concern-2")));

    store.save(replacement);

    assertEquals(replacement, store.load().orElseThrow());
  }

  @Test
  public void missingMemoryIsDistinctFromEmptyMemoryAndClearIsScoped() {
    assertTrue(store.load().isEmpty());
    store.save(new ReviewFeedbackMemory());
    ReviewFeedbackMemoryStore otherStore =
        new ReviewFeedbackMemoryStore(getTestReviewAiDb(), "change-2");
    otherStore.save(new ReviewFeedbackMemory());

    store.clear();

    assertTrue(store.load().isEmpty());
    assertFalse(otherStore.load().isEmpty());
  }

  @Test
  public void ignoresUnsupportedStoredSchema() throws Exception {
    store.save(new ReviewFeedbackMemory());
    try (Connection connection = getTestReviewAiDb().getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "UPDATE review_feedback_memories SET schema_version = ? WHERE change_id = ?")) {
      statement.setInt(1, ReviewFeedbackMemory.CURRENT_SCHEMA_VERSION + 1);
      statement.setString(2, "change-1");
      statement.executeUpdate();
    }

    assertTrue(store.load().isEmpty());
  }

  @Test
  public void rejectsUnsupportedSchemaAndInvalidConcernFeedback() throws Exception {
    ReviewFeedbackMemory unsupported = new ReviewFeedbackMemory();
    unsupported.setSchemaVersion(ReviewFeedbackMemory.CURRENT_SCHEMA_VERSION + 1);
    assertRejected(() -> store.save(unsupported));

    ReviewFeedbackMemory invalid = loadMemory();
    invalid.setConcernFeedback(Map.of(" ", invalid.getGenericFeedback()));
    assertRejected(() -> store.save(invalid));

    ReviewFeedbackMemory invalidAgent = loadMemory();
    invalidAgent.setDisabledSpecializedAgents(Set.of(" "));
    assertRejected(() -> store.save(invalidAgent));
  }

  private static ReviewFeedbackMemory loadMemory() throws Exception {
    return loadMemory(MEMORY_RESOURCE);
  }

  private static ReviewFeedbackMemory loadMemory(String resource) throws Exception {
    String json =
        Files.readString(TestResourceLoader.getTestResourcePath().resolve(resource));
    return getGson().fromJson(json, ReviewFeedbackMemory.class);
  }

  private static void assertRejected(Runnable operation) {
    try {
      operation.run();
      fail("Expected invalid review feedback memory to be rejected");
    } catch (IllegalArgumentException expected) {
      // Expected.
    }
  }
}
