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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.reflect.TypeToken;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.extensions.api.changes.ChangeApi.CommentsRequest;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.json.OutputFormat;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewBatch;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.GerritReviewException;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class GerritClientReviewTest {
  @Mock private Configuration config;
  @Mock private PluginDataHandlerProvider pluginDataHandlerProvider;
  @Mock private Localizer localizer;
  @Mock private GerritApi gerritApi;
  @Mock private Changes changes;
  @Mock private ChangeApi changeApi;
  @Mock private RevisionApi revisionApi;
  @Mock private ReviewResult reviewResult;
  @Mock private CommentsRequest commentsRequest;

  private GerritClientReview client;
  private GerritChange change;
  private ChangeSetData changeSetData;

  @Before
  public void setUp() throws Exception {
    change =
        new GerritChange(
            "project", BranchNameKey.create("project", "main"), Change.key("I1234567890"));
    changeSetData = new ChangeSetData(1);
    when(config.getGerritApi()).thenReturn(gerritApi);
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id("project", "main", "I1234567890")).thenReturn(changeApi);
    when(changeApi.current()).thenReturn(revisionApi);
    when(revisionApi.review(any(ReviewInput.class))).thenReturn(reviewResult);
    client = new GerritClientReview(config, pluginDataHandlerProvider, localizer);
  }

  @Test
  public void successfulReviewReturnsNormally() throws Exception {
    client.setReview(change, List.of(new ReviewBatch("Review comment")), changeSetData);

    verify(revisionApi).review(any(ReviewInput.class));
  }

  @Test
  public void reviewResultErrorIsSurfaced() throws Exception {
    reviewResult.error = "submission rejected";

    try {
      client.setReview(change, List.of(new ReviewBatch("Review comment")), changeSetData);
      fail("Expected Gerrit review failure");
    } catch (GerritReviewException e) {
      assertEquals("submission rejected", e.getMessage());
    }
  }

  @Test
  public void returnsPublishedCommentIdsByConcern() throws Exception {
    Map<String, List<CommentInfo>> comments = readPublishedComments();
    List<ReviewBatch> batches = new ArrayList<>();
    Map<String, String> expectedCommentIds = new LinkedHashMap<>();
    comments.forEach(
        (filename, filenameComments) ->
            filenameComments.stream()
                .filter(comment -> comment.tag == null)
                .forEach(
                    comment -> {
                      ReviewBatch batch = new ReviewBatch(comment.message);
                      batch.setConcernId("concern-" + comment.id);
                      batch.setId(comment.inReplyTo);
                      batch.setLine(comment.line);
                      if (!batch.getFilename().equals(filename)) {
                        batch.setFilename(filename);
                      }
                      batches.add(batch);
                      expectedCommentIds.put(batch.getConcernId(), comment.id);
                    }));
    when(changeApi.commentsRequest()).thenReturn(commentsRequest);
    when(commentsRequest.get()).thenReturn(comments);
    when(revisionApi.review(any(ReviewInput.class)))
        .thenAnswer(
            invocation -> {
              ReviewInput input = invocation.getArgument(0);
              assertNotNull(input.tag);
              comments.values().stream()
                  .flatMap(List::stream)
                  .filter(comment -> comment.tag == null)
                  .forEach(comment -> comment.tag = input.tag);
              return reviewResult;
            });

    assertEquals(
        expectedCommentIds,
        client.setReviewAndGetPublishedCommentIds(change, batches, changeSetData, null));
  }

  private static Map<String, List<CommentInfo>> readPublishedComments() throws Exception {
    Type commentsType = new TypeToken<Map<String, List<CommentInfo>>>() {}.getType();
    String json =
        Files.readString(
            TestResourceLoader.getTestResourcePath()
                .resolve("__files/gerritPublishedConcernComments.json"));
    return OutputFormat.JSON.newGson().fromJson(json, commentsType);
  }
}
