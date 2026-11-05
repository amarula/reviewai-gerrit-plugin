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

import com.google.gerrit.extensions.api.changes.ChangeApi;
import com.google.gerrit.extensions.api.changes.RevisionApi;
import com.google.gerrit.extensions.common.FileInfo;
import com.google.gerrit.extensions.restapi.BadRequestException;
import com.google.gerrit.extensions.restapi.BinaryResult;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.eclipse.jgit.lib.FileMode;

/** Resolves a changed file or line range pinned to an immutable patch-set revision. */
public final class GerritCodeContext {
  public static final int MAX_FILE_BYTES = 64 * 1024;
  private static final String TAG_PREFIX = "reviewai:code-context:";

  private GerritCodeContext() {}

  /** File reference stored on the Gerrit comment and in sidebar conversation history. */
  public record Attachment(
      String revision,
      String path,
      @SerializedName(
              value = "start_line",
              alternate = {"startLine"})
          Integer startLine,
      @SerializedName(
              value = "end_line",
              alternate = {"endLine"})
          Integer endLine) {
    public Attachment(String revision, String path) {
      this(revision, path, null, null);
    }
  }

  public static String toTag(Attachment attachment) throws BadRequestException {
    validateReference(attachment);
    return TAG_PREFIX
        + Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(getGson().toJson(attachment).getBytes(UTF_8));
  }

  public static Optional<Attachment> fromTag(String tag) throws BadRequestException {
    if (tag == null || !tag.startsWith(TAG_PREFIX)) {
      return Optional.empty();
    }
    try {
      Attachment attachment =
          getGson()
              .fromJson(
                  new String(
                      Base64.getUrlDecoder().decode(tag.substring(TAG_PREFIX.length())), UTF_8),
                  Attachment.class);
      validateReference(attachment);
      return Optional.of(attachment);
    } catch (IllegalArgumentException | JsonParseException e) {
      throw new BadRequestException("Invalid code context attachment", e);
    }
  }

  /**
   * Uses Gerrit's revision API so visibility and patch-set membership are checked on every read.
   */
  public static String read(ChangeApi change, Attachment attachment) throws Exception {
    validateReference(attachment);
    RevisionApi revision = change.revision(attachment.revision());
    FileInfo file = revision.files().get(attachment.path());
    if (file == null || Character.valueOf('D').equals(file.status)) {
      throw new BadRequestException("Code context must be an existing changed file");
    }
    if (Boolean.TRUE.equals(file.binary)
        || (file.newMode != null && (file.newMode & FileMode.TYPE_MASK) != FileMode.TYPE_FILE)) {
      throw new BadRequestException("Code context must be a regular text file");
    }
    if (file.size > MAX_FILE_BYTES) {
      throw new BadRequestException("Code context file exceeds 64 KiB");
    }
    try (BinaryResult content = revision.file(attachment.path()).content()) {
      if (content.getContentLength() > MAX_FILE_BYTES) {
        throw new BadRequestException("Code context file exceeds 64 KiB");
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      content.writeTo(
          new OutputStream() {
            @Override
            public void write(int value) throws IOException {
              write(new byte[] {(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] values, int offset, int length) throws IOException {
              if (length > MAX_FILE_BYTES - bytes.size()) {
                throw new FileTooLargeException();
              }
              bytes.write(values, offset, length);
            }
          });
      byte[] data = bytes.toByteArray();
      for (byte value : data) {
        if (value == 0) {
          throw new BadRequestException("Code context must be a regular text file");
        }
      }
      try {
        return selectLines(attachment, UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString());
      } catch (CharacterCodingException e) {
        throw new BadRequestException("Code context must be UTF-8 text", e);
      }
    } catch (FileTooLargeException e) {
      throw new BadRequestException(e.getMessage(), e);
    }
  }

  public static String format(Attachment attachment, String content) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("revision", attachment.revision());
    context.put("path", attachment.path());
    if (attachment.startLine() != null) {
      context.put("start_line", attachment.startLine());
      context.put("end_line", attachment.endLine());
    }
    context.put("content", content);
    return "\n\nAttached file (code context):\n" + getGson().toJson(context);
  }

  private static String selectLines(Attachment attachment, String content)
      throws BadRequestException {
    if (attachment.startLine() == null) {
      return content;
    }
    String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    int lineCount = lines.length - (lines[lines.length - 1].isEmpty() ? 1 : 0);
    if (attachment.endLine() > lineCount) {
      throw new BadRequestException("Code context line range exceeds the file's line count");
    }
    return String.join(
        "\n", Arrays.copyOfRange(lines, attachment.startLine() - 1, attachment.endLine()));
  }

  private static void validateReference(Attachment attachment) throws BadRequestException {
    if (attachment == null
        || attachment.revision() == null
        || !attachment.revision().matches("[0-9a-f]{40}")
        || attachment.path() == null
        || attachment.path().isBlank()
        || attachment.path().length() > 4096
        || attachment.path().startsWith("/")
        || attachment.path().indexOf('\0') >= 0
        || Arrays.stream(attachment.path().split("/", -1))
            .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))) {
      throw new BadRequestException(
          "Code context requires a revision SHA and repository file path");
    }
    if ((attachment.startLine() == null) != (attachment.endLine() == null)
        || (attachment.startLine() != null
            && (attachment.startLine() < 1 || attachment.endLine() < attachment.startLine()))) {
      throw new BadRequestException("Code context requires an inclusive, one-based line range");
    }
  }

  private static final class FileTooLargeException extends IOException {
    FileTooLargeException() {
      super("Code context file exceeds 64 KiB");
    }
  }
}
