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

package com.googlesource.gerrit.plugins.reviewai.listener;

import static org.junit.Assert.assertEquals;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequest;
import java.util.Map;
import org.junit.Test;

public class AiRequestIntakeClassifierTest {
  @Test
  public void rejectsConcurrentPatchSetReview() {
    assertPersistent(
        AiRequestIntakeClassifier.patchSetReview(),
        AiRequest.Kind.REVIEW,
        AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED);
  }

  @Test
  public void ignoresUnaddressedComment() {
    AiRequestIntakeDecision decision =
        AiRequestIntakeClassifier.comment(false, false, changeSetData());

    assertEquals(AiRequestIntakeDecision.Disposition.IGNORE, decision.disposition());
  }

  @Test
  public void queuesAddressedAiMessage() {
    assertPersistent(
        AiRequestIntakeClassifier.comment(true, false, changeSetData()),
        AiRequest.Kind.MESSAGE,
        AiRequest.AdmissionPolicy.QUEUE);
  }

  @Test
  public void rejectsConcurrentReviewCommand() {
    ChangeSetData data = changeSetData();
    addCommand(data, CommandSet.REVIEW);

    assertPersistent(
        AiRequestIntakeClassifier.comment(true, false, data),
        AiRequest.Kind.REVIEW,
        AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED);
  }

  @Test
  public void rejectsConcurrentSuggestCommand() {
    ChangeSetData data = changeSetData();
    addCommand(data, CommandSet.SUGGEST);

    assertPersistent(
        AiRequestIntakeClassifier.comment(true, false, data),
        AiRequest.Kind.SUGGEST,
        AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED);
  }

  @Test
  public void handlesMutationsWithoutEnteringQueue() {
    for (CommandSet command :
        new CommandSet[] {
          CommandSet.FORGET_THREAD, CommandSet.CONFIGURE, CommandSet.DIRECTIVES
        }) {
      ChangeSetData data = changeSetData();
      addCommand(data, command);

      assertEquals(
          AiRequestIntakeDecision.Disposition.DIRECT,
          AiRequestIntakeClassifier.comment(true, false, data).disposition());
    }
  }

  @Test
  public void handlesHelpWithoutEnteringQueue() {
    ChangeSetData data = changeSetData();
    addCommand(data, CommandSet.HELP);

    assertEquals(
        AiRequestIntakeDecision.Disposition.DIRECT,
        AiRequestIntakeClassifier.comment(true, false, data).disposition());
  }

  @Test
  public void deferredReviewTakesPrecedenceOverAddressing() {
    assertPersistent(
        AiRequestIntakeClassifier.comment(false, true, changeSetData()),
        AiRequest.Kind.REVIEW,
        AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED);
  }

  private static ChangeSetData changeSetData() {
    return new ChangeSetData(1000);
  }

  private static void addCommand(ChangeSetData data, CommandSet command) {
    data.addParsedCommand(ClientCommandBase.commandName(command), Map.of());
  }

  private static void assertPersistent(
      AiRequestIntakeDecision decision,
      AiRequest.Kind kind,
      AiRequest.AdmissionPolicy policy) {
    assertEquals(AiRequestIntakeDecision.Disposition.PERSIST, decision.disposition());
    assertEquals(kind, decision.kind());
    assertEquals(policy, decision.admissionPolicy());
  }
}
