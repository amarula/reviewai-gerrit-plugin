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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.patch.filename;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.GerritClientData;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import java.util.List;
import org.junit.Test;

public class FilenameSanitizerTest {
  @Test
  public void acceptsAFileInTheRevision() {
    assertTrue(sanitizer(List.of("src/A.java")).sanitizeFilename(reply("src/A.java")));
  }

  @Test
  public void reportsAFileThatLeftTheRevision() {
    // The caller has to know: the finding must not be sent to Gerrit under this path, and warning
    // alone left the invalid name on the item for the caller to publish anyway.
    assertFalse(sanitizer(List.of("src/A.java")).sanitizeFilename(reply("src/Gone.java")));
  }

  @Test
  public void leavesTheUnanchoredFilenameInPlaceForTheCallerToReport() {
    AiReplyItem reply = reply("src/Gone.java");

    sanitizer(List.of("src/A.java")).sanitizeFilename(reply);

    assertEquals("src/Gone.java", reply.getFilename());
  }

  @Test
  public void rewritesASuffixMatchToTheFullPath() {
    AiReplyItem reply = reply("A.java");

    assertTrue(sanitizer(List.of("src/A.java")).sanitizeFilename(reply));

    assertEquals("src/A.java", reply.getFilename());
  }

  @Test
  public void acceptsAnItemThatNamesNoFile() {
    assertTrue(sanitizer(List.of("src/A.java")).sanitizeFilename(AiReplyItem.builder().build()));
  }

  @Test
  public void acceptsEverythingWhenTheFileListIsUnknown() {
    // Refusing here would drop the whole review because a lookup failed.
    assertTrue(sanitizer(null).sanitizeFilename(reply("anything.java")));
    assertTrue(sanitizer(List.of()).sanitizeFilename(reply("anything.java")));
  }

  @Test
  public void patchSetLevelIsAlwaysPartOfTheRevision() {
    // Gerrit's own key for a change-level comment, not a path: it is valid whatever the file list
    // says, and treating it as absent would dismiss every concern raised without a file.
    FilenameSanitizer sanitizer = sanitizer(List.of("src/A.java"));

    assertTrue(sanitizer.isPartOfPatchSet("/PATCHSET_LEVEL"));
    assertTrue(sanitizer.sanitizeFilename(reply("/PATCHSET_LEVEL")));
  }

  @Test
  public void aMissingNameIsNotPartOfTheRevision() {
    FilenameSanitizer sanitizer = sanitizer(List.of("src/A.java"));

    assertFalse(sanitizer.isPartOfPatchSet(null));
    assertFalse(sanitizer.isPartOfPatchSet(""));
    assertFalse(sanitizer.isPartOfPatchSet("src/Gone.java"));
    assertTrue(sanitizer.isPartOfPatchSet("src/A.java"));
  }

  private static AiReplyItem reply(String filename) {
    return AiReplyItem.builder().filename(filename).build();
  }

  private static FilenameSanitizer sanitizer(List<String> patchSetFiles) {
    IGerritClientPatchSet patchSet = mock(IGerritClientPatchSet.class);
    when(patchSet.getPatchSetFiles()).thenReturn(patchSetFiles);
    GerritClientData clientData = mock(GerritClientData.class);
    when(clientData.getGerritClientPatchSet()).thenReturn(patchSet);
    GerritClient gerritClient = mock(GerritClient.class);
    when(gerritClient.getClientData(any())).thenReturn(clientData);
    return new FilenameSanitizer(gerritClient, mock(GerritChange.class));
  }
}
