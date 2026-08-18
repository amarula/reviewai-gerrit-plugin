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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.reflect.TypeToken;
import com.google.gerrit.entities.Account;
import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.entities.Change;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.ChangeApi.CommentsRequest;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.common.CommentInfo;
import com.google.gerrit.json.OutputFormat;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.events.CommentAddedEvent;
import com.google.gerrit.server.util.ManualRequestContext;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class GerritClientCommentsTest {
  private static final int AI_ACCOUNT_ID = 1000;
  private static final int REVIEWER_ACCOUNT_ID = 2000;
  private static final long LATEST_COMMENT_TIMESTAMP = 1699271271L;
  private static final String COMMENTS_RESOURCE =
      "__files/gerritImplicitReplyComments.json";
  private static final String RESOLVED_REPLY_RESOURCE =
      "__files/gerritImplicitResolvedReplyComments.json";
  private static final Type COMMENTS_TYPE =
      new TypeToken<Map<String, List<CommentInfo>>>() {}.getType();

  private GerritClientComments client;
  private GerritChange change;
  private Map<String, List<CommentInfo>> comments;
  private CommentsRequest commentsRequest;

  @Before
  public void setUp() throws Exception {
    comments = readComments();
    commentsRequest = mock(CommentsRequest.class);
    when(commentsRequest.get()).thenReturn(comments);

    Configuration config = mock(Configuration.class);
    when(config.getUserId()).thenReturn(Account.id(AI_ACCOUNT_ID));
    when(config.getGerritUserName()).thenReturn("reviewai");
    when(config.getGerritUserEmail()).thenReturn("");
    when(config.openRequestContext()).thenReturn(mock(ManualRequestContext.class));

    GerritApi gerritApi = mock(GerritApi.class);
    Changes changes = mock(Changes.class);
    ChangeApi changeApi = mock(ChangeApi.class);
    when(config.getGerritApi()).thenReturn(gerritApi);
    when(gerritApi.changes()).thenReturn(changes);
    when(changes.id("project", "main", "change-id")).thenReturn(changeApi);
    when(changeApi.commentsRequest()).thenReturn(commentsRequest);

    CommentAddedEvent event = mock(CommentAddedEvent.class);
    event.author = GerritClientCommentsTest::reviewer;

    change = mock(GerritChange.class);
    when(change.getEvent()).thenReturn(event);
    when(change.getEventTimeStamp()).thenReturn(LATEST_COMMENT_TIMESTAMP);
    when(change.getProjectName()).thenReturn("project");
    when(change.getBranchNameKey())
        .thenReturn(BranchNameKey.create(Project.nameKey("project"), "main"));
    when(change.getChangeKey()).thenReturn(Change.key("change-id"));
    when(change.getFullChangeId()).thenReturn("project~main~change-id");

    client =
        new GerritClientComments(
            config,
            new ChangeSetData(AI_ACCOUNT_ID),
            mock(ICodeContextPolicy.class),
            mock(IGerritClientPatchSet.class),
            mock(PluginDataHandlerProvider.class),
            mock(Localizer.class));
  }

  @Test
  public void addressedCommentsRemainEventLocal() {
    assertTrue(client.retrieveLastComments(change, false));

    assertEquals(1, client.getCommentProperties().size());
    assertEquals("latest-reply", client.getCommentProperties().getFirst().getId());
    assertEquals(
        "latest-reply",
        client.getCommentData().getAddressedComments().getFirst().getId());

    client.retrieveAllComments(change);

    assertTrue(client.getCommentData().getAddressedComments().isEmpty());
  }

  @Test
  public void addressedCommandIsRetainedForFeedbackClassification() {
    latestComment().message = "/review";

    assertFalse(client.retrieveLastComments(change, false));

    assertTrue(client.getCommentProperties().isEmpty());
    assertEquals(
        "latest-reply",
        client.getCommentData().getAddressedComments().getFirst().getId());
  }

  @Test
  public void replyToHumanWithoutMentionIsIgnored() throws Exception {
    latestComment().inReplyTo = "human-parent";
    when(commentsRequest.get()).thenReturn(comments);

    assertFalse(client.retrieveLastComments(change, false));
  }

  @Test
  public void topLevelCommentWithoutMentionIsIgnored() throws Exception {
    latestComment().inReplyTo = null;
    when(commentsRequest.get()).thenReturn(comments);

    assertFalse(client.retrieveLastComments(change, false));
  }

  @Test
  public void resolvedReplyToAssistantIsIgnored() throws Exception {
    comments = readComments(RESOLVED_REPLY_RESOURCE);
    when(commentsRequest.get()).thenReturn(comments);

    assertFalse(client.retrieveLastComments(change, false));
  }

  private CommentInfo latestComment() {
    return comments.get("src/Test.java").getLast();
  }

  private static Map<String, List<CommentInfo>> readComments() throws IOException {
    return readComments(COMMENTS_RESOURCE);
  }

  private static Map<String, List<CommentInfo>> readComments(String resource) throws IOException {
    String json =
        Files.readString(TestResourceLoader.getTestResourcePath().resolve(resource));
    return OutputFormat.JSON.newGson().fromJson(json, COMMENTS_TYPE);
  }

  private static AccountAttribute reviewer() {
    AccountAttribute reviewer = new AccountAttribute();
    reviewer.accountId = REVIEWER_ACCOUNT_ID;
    reviewer.username = "reviewer";
    reviewer.email = "reviewer@example.com";
    return reviewer;
  }
}
