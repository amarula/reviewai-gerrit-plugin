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

package com.googlesource.gerrit.plugins.reviewai.listener;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.gerrit.extensions.client.ChangeKind;
import com.google.gerrit.server.data.AccountAttribute;
import com.google.gerrit.server.data.PatchSetAttribute;
import com.googlesource.gerrit.plugins.reviewai.PatchSetReviewer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.listener.IEventHandlerType.PreprocessResult;
import java.util.Optional;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class EventHandlerTypePatchSetReviewTest {
  @Parameterized.Parameters(name = "{0}")
  public static Object[] reviewableCommitMessageChangeKinds() {
    return new Object[] {ChangeKind.NO_CODE_CHANGE, ChangeKind.TRIVIAL_REBASE_WITH_MESSAGE_UPDATE};
  }

  @Parameterized.Parameter public ChangeKind changeKind;

  @Test
  public void reviewsCommitMessageChange() throws Exception {
    Configuration config = mock(Configuration.class);
    ChangeSetData changeSetData = mock(ChangeSetData.class);
    GerritChange change = mock(GerritChange.class);
    PatchSetReviewer reviewer = mock(PatchSetReviewer.class);
    GerritClient gerritClient = mock(GerritClient.class);
    PatchSetAttribute patchSet = new PatchSetAttribute();
    patchSet.kind = changeKind;
    patchSet.author = new AccountAttribute();
    patchSet.author.username = "author";

    when(config.getAiReviewPatchSet()).thenReturn(true);
    when(change.getPatchSetAttribute()).thenReturn(Optional.of(patchSet));

    EventHandlerTypePatchSetReview handler =
        new EventHandlerTypePatchSetReview(config, changeSetData, change, reviewer, gerritClient);

    assertEquals(PreprocessResult.OK, handler.preprocessEvent());
    handler.processEvent();

    verify(gerritClient).retrievePatchSetInfo(change);
    verify(reviewer).review(change);
  }
}
