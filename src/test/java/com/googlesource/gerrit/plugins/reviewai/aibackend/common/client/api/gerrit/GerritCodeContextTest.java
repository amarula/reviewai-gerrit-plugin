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

import static com.googlesource.gerrit.plugins.reviewai.utils.GsonUtils.getGson;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.api.GerritApi;
import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.Changes;
import com.google.gerrit.extensions.api.changes.FileApi;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.common.FileInfo;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gson.JsonObject;
import com.googlesource.gerrit.plugins.reviewai.TestBase;
import com.googlesource.gerrit.plugins.reviewai.TestResourceLoader;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;

public class GerritCodeContextTest extends TestBase {
  private ChangeApi change;
  private RevisionApi revision;
  private FileApi fileApi;
  private FileInfo file;
  private GerritCodeContext.Attachment attachment;
  private String content;
  private String message;
  private JsonObject ranges;
  private JsonObject multiple;

  @Before
  public void setUp() throws Exception {
    JsonObject fixture =
        getGson()
            .fromJson(
                Files.readString(
                    TestResourceLoader.getTestResourcePath()
                        .resolve("__files/reviewagent/code-context.json")),
                JsonObject.class);
    attachment = getGson().fromJson(fixture, GerritCodeContext.Attachment.class);
    content = fixture.get("content").getAsString();
    message = fixture.get("message").getAsString();
    ranges =
        getGson()
            .fromJson(
                Files.readString(
                    TestResourceLoader.getTestResourcePath()
                        .resolve("__files/reviewagent/code-context-ranges.json")),
                JsonObject.class);
    multiple =
        getGson()
            .fromJson(
                Files.readString(
                    TestResourceLoader.getTestResourcePath()
                        .resolve("__files/reviewagent/code-context-multiple.json")),
                JsonObject.class);
    change = mock(ChangeApi.class);
    revision = mock(RevisionApi.class);
    fileApi = mock(FileApi.class);
    file = new FileInfo();
    file.size = content.getBytes(UTF_8).length;
    file.newMode = 0100644;
    when(change.revision(attachment.revision())).thenReturn(revision);
    when(revision.files()).thenReturn(Map.of(attachment.path(), file));
    when(revision.file(attachment.path())).thenReturn(fileApi);
    when(fileApi.content()).thenAnswer(ignored -> BinaryResult.create(content));
  }

  @Test
  public void readsWholeFileFromPinnedRevision() throws Exception {
    assertEquals(content, GerritCodeContext.read(change, attachment));
    verify(change, never()).current();
  }

  @Test
  public void readsOnlyTheInclusiveRangeAndPreservesItInTheCommentTag() throws Exception {
    attachment = rangeAttachment();
    String selected = GerritCodeContext.read(change, attachment);
    assertEquals(ranges.get("content").getAsString(), selected);
    assertEquals(
        List.of(attachment), GerritCodeContext.fromTag(GerritCodeContext.toTag(attachment)));
    JsonObject formatted =
        getGson()
            .fromJson(
                GerritCodeContext.format(attachment, selected).split("\n", 4)[3], JsonObject.class);
    assertEquals(attachment.startLine().intValue(), formatted.get("start_line").getAsInt());
    assertEquals(attachment.endLine().intValue(), formatted.get("end_line").getAsInt());
    assertEquals(selected, formatted.get("content").getAsString());
    verify(change, never()).current();
  }

  @Test
  public void rejectsInvalidAndOutOfBoundsRanges() {
    for (var invalid : ranges.getAsJsonArray("invalid_ranges")) {
      JsonObject reference = invalid.getAsJsonObject().deepCopy();
      reference.addProperty("revision", attachment.revision());
      reference.addProperty("path", attachment.path());
      var invalidAttachment = getGson().fromJson(reference, GerritCodeContext.Attachment.class);
      assertThrows(
          BadRequestException.class, () -> GerritCodeContext.read(change, invalidAttachment));
    }
  }

  @Test
  public void countsRealLinesWithOrWithoutAFinalNewline() throws Exception {
    var lastLine = new GerritCodeContext.Attachment(attachment.revision(), attachment.path(), 2, 2);
    var beyondEnd =
        new GerritCodeContext.Attachment(attachment.revision(), attachment.path(), 3, 3);
    for (var example : ranges.getAsJsonArray("line_endings")) {
      var value = example.getAsJsonObject();
      content = value.get("content").getAsString();
      assertEquals(value.get("last_line").getAsString(), GerritCodeContext.read(change, lastLine));
      assertThrows(BadRequestException.class, () -> GerritCodeContext.read(change, beyondEnd));
    }
  }

  @Test
  public void emptyFilesSupportWholeFileContextButHaveNoSelectableLines() throws Exception {
    content = ranges.get("empty_content").getAsString();
    assertEquals(content, GerritCodeContext.read(change, attachment));
    var range = new GerritCodeContext.Attachment(attachment.revision(), attachment.path(), 1, 1);
    assertThrows(BadRequestException.class, () -> GerritCodeContext.read(change, range));
  }

  @Test
  public void immutableReferenceSurvivesCommentTagRoundTrip() throws Exception {
    assertEquals(
        List.of(attachment), GerritCodeContext.fromTag(GerritCodeContext.toTag(attachment)));
    assertTrue(GerritCodeContext.fromTag(null).isEmpty());
    assertTrue(GerritCodeContext.fromTag("autogenerated:reviewai").isEmpty());
    assertFalse(GerritCodeContext.toTag(attachment).contains(content));
  }

  @Test
  public void rejectsUnchangedAndDeletedFiles() throws Exception {
    when(revision.files()).thenReturn(Map.of());
    assertThrows(BadRequestException.class, () -> GerritCodeContext.read(change, attachment));
    when(revision.files()).thenReturn(Map.of(attachment.path(), file));
    file.status = 'D';
    assertThrows(BadRequestException.class, () -> GerritCodeContext.read(change, attachment));
    verify(revision, never()).file(attachment.path());
  }

  @Test
  public void rejectsBinaryFilesAndSymlinks() {
    file.binary = true;
    assertThrows(BadRequestException.class, () -> GerritCodeContext.read(change, attachment));
    file.binary = false;
    file.newMode = 0120000;
    assertThrows(BadRequestException.class, () -> GerritCodeContext.read(change, attachment));
  }

  @Test
  public void rejectsOversizedFilesBeforeReadingContent() throws Exception {
    file.size = GerritCodeContext.MAX_FILE_BYTES + 1;
    assertThrows(BadRequestException.class, () -> GerritCodeContext.read(change, attachment));
    verify(fileApi, never()).content();
  }

  @Test
  public void boundsStreamsEvenWhenTheirLengthIsUnknown() throws Exception {
    when(fileApi.content())
        .thenReturn(
            new BinaryResult() {
              @Override
              public void writeTo(OutputStream output) throws IOException {
                byte[] chunk = content.getBytes(UTF_8);
                for (int total = 0;
                    total <= GerritCodeContext.MAX_FILE_BYTES;
                    total += chunk.length) {
                  output.write(chunk);
                }
              }
            });
    assertThrows(BadRequestException.class, () -> GerritCodeContext.read(change, attachment));
  }

  @Test
  public void rejectsInvalidUtf8AndBinaryContentEvenWhenNotMarkedBinary() throws Exception {
    when(fileApi.content()).thenReturn(BinaryResult.create(new byte[] {(byte) 0xff}));
    assertThrows(BadRequestException.class, () -> GerritCodeContext.read(change, attachment));
    when(fileApi.content()).thenReturn(BinaryResult.create(new byte[] {0}));
    assertThrows(BadRequestException.class, () -> GerritCodeContext.read(change, attachment));
  }

  @Test
  public void rejectsFloatingRevisionsAndInvalidPaths() {
    assertThrows(
        BadRequestException.class,
        () ->
            GerritCodeContext.read(
                change, new GerritCodeContext.Attachment("current", attachment.path())));
    for (String path :
        new String[] {"/COMMIT_MSG", "../secret", "src/../secret", "src//file", ""}) {
      assertThrows(
          BadRequestException.class,
          () ->
              GerritCodeContext.read(
                  change, new GerritCodeContext.Attachment(attachment.revision(), path)));
    }
  }

  @Test
  public void propagatesRevisionAccessDenial() throws Exception {
    when(change.revision(attachment.revision())).thenThrow(new AuthException("denied"));
    assertThrows(AuthException.class, () -> GerritCodeContext.read(change, attachment));
  }

  @Test
  public void persistedCommentMetadataIsResolvedWithoutReviewChange() throws Exception {
    assertPersistedContextInPrompt(content);
  }

  @Test
  public void persistedRangeIncludesOnlySelectedLinesInTheAiRequest() throws Exception {
    attachment = rangeAttachment();
    assertPersistedContextInPrompt(ranges.get("content").getAsString());
  }

  @Test
  public void multipleFilesAndRangesSurviveTheTagAndReachTheAiRequest() throws Exception {
    List<GerritCodeContext.Attachment> attachments = multipleAttachments();
    String tag = GerritCodeContext.toTag(attachments);
    assertEquals(attachments, GerritCodeContext.fromTag(tag));
    var other = attachments.getLast();
    var otherApi = mock(FileApi.class);
    when(revision.files()).thenReturn(Map.of(attachment.path(), file, other.path(), file));
    when(revision.file(other.path())).thenReturn(otherApi);
    when(otherApi.content())
        .thenAnswer(ignored -> BinaryResult.create(multiple.get("other_content").getAsString()));
    GerritComment comment = new GerritComment();
    comment.setMessage(message);
    comment.setTag(tag);
    String prompt = promptBuilder().getCleanedMessage(comment);
    String[] blocks = prompt.split("\n\nAttached file \\(code context\\):\n");
    assertEquals(attachments.size() + 1, blocks.length);
    for (int index = 0; index < attachments.size(); index++) {
      JsonObject context = getGson().fromJson(blocks[index + 1], JsonObject.class);
      assertEquals(attachments.get(index).path(), context.get("path").getAsString());
      assertEquals(attachments.get(index).revision(), context.get("revision").getAsString());
      assertEquals(
          multiple.getAsJsonArray("contents").get(index).getAsString(),
          context.get("content").getAsString());
    }
  }

  @Test
  public void duplicatesAreIgnoredWithoutMergingDistinctRanges() throws Exception {
    var attachments = new java.util.ArrayList<>(multipleAttachments());
    var expected = List.copyOf(attachments);
    attachments.add(attachments.getFirst());
    assertEquals(expected, GerritCodeContext.fromTag(GerritCodeContext.toTag(attachments)));
  }

  @Test
  public void rejectsExcessiveOrInvalidAttachmentLists() {
    assertThrows(
        BadRequestException.class,
        () ->
            GerritCodeContext.toTag(
                Collections.nCopies(GerritCodeContext.MAX_ATTACHMENTS + 1, attachment)));
    assertThrows(
        BadRequestException.class,
        () -> GerritCodeContext.toTag(java.util.Arrays.asList(attachment, null)));
    String invalid =
        Base64.getUrlEncoder()
            .encodeToString(
                getGson().toJson(java.util.Arrays.asList(attachment, null)).getBytes(UTF_8));
    assertThrows(
        BadRequestException.class,
        () -> GerritCodeContext.fromTag("reviewai:code-context:" + invalid));
  }

  private List<GerritCodeContext.Attachment> multipleAttachments() {
    return multiple.getAsJsonArray("attachments").asList().stream()
        .map(
            value -> {
              JsonObject reference = value.getAsJsonObject().deepCopy();
              reference.addProperty("revision", attachment.revision());
              return getGson().fromJson(reference, GerritCodeContext.Attachment.class);
            })
        .toList();
  }

  private GerritCodeContext.Attachment rangeAttachment() {
    return new GerritCodeContext.Attachment(
        attachment.revision(), attachment.path(),
        ranges.get("start_line").getAsInt(), ranges.get("end_line").getAsInt());
  }

  private void assertPersistedContextInPrompt(String expectedContent) throws Exception {
    GerritComment comment = new GerritComment();
    comment.setMessage(message);
    comment.setTag(GerritCodeContext.toTag(attachment));
    String prompt = promptBuilder().getCleanedMessage(comment);
    assertTrue(prompt.contains(attachment.path()));
    assertTrue(prompt.contains(attachment.revision()));
    JsonObject attached =
        getGson().fromJson(prompt.substring(prompt.indexOf("{\"")), JsonObject.class);
    assertEquals(expectedContent, attached.get("content").getAsString());
    assertTrue(prompt.stripLeading().startsWith(message.substring("/message ".length())));
  }

  private AiComment promptBuilder() throws Exception {
    Configuration config = mock(Configuration.class);
    when(config.getGerritUserName()).thenReturn("gpt");
    when(config.getGerritUserEmail()).thenReturn("");
    when(config.getLocaleDefault()).thenReturn(Locale.ENGLISH);
    GerritApi api = mock(GerritApi.class);
    Changes changes = mock(Changes.class);
    when(config.getGerritApi()).thenReturn(api);
    when(api.changes()).thenReturn(changes);
    when(changes.id(PROJECT_NAME.get(), BRANCH_NAME.shortName(), CHANGE_ID.get()))
        .thenReturn(change);
    ChangeSetData data = new ChangeSetData(7);
    // A fresh prompt builder reloads the persisted tag, with no browser or request-local state.
    // Supply the owning change explicitly, leaving reviewChange unset in the copied data.
    return new AiComment(config, data.copy(), getGerritChange(), new Localizer(config));
  }

  @Test
  public void rejectsMalformedAttachmentTags() {
    assertThrows(
        BadRequestException.class, () -> GerritCodeContext.fromTag("reviewai:code-context:!"));
    String malformed = Base64.getUrlEncoder().encodeToString("{".getBytes(UTF_8));
    assertThrows(
        BadRequestException.class,
        () -> GerritCodeContext.fromTag("reviewai:code-context:" + malformed));
  }
}
