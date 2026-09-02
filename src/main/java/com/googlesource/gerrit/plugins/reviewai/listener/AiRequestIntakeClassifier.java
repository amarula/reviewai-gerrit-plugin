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

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.commands.ClientCommandBase.CommandSet;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequest;

/** Pure request routing policy applied after comment command parsing but before execution. */
public final class AiRequestIntakeClassifier {
  private AiRequestIntakeClassifier() {}

  public static AiRequestIntakeDecision patchSetReview() {
    return rejectedWhenOccupied(AiRequest.Kind.REVIEW);
  }

  public static AiRequestIntakeDecision comment(
      boolean addressed, boolean startsDeferredReview, ChangeSetData changeSetData) {
    if (startsDeferredReview) {
      return rejectedWhenOccupied(AiRequest.Kind.REVIEW);
    }
    if (!addressed) {
      return AiRequestIntakeDecision.ignored();
    }
    if (changeSetData.hasParsedCommand(CommandSet.SUGGEST)
        || Boolean.TRUE.equals(changeSetData.getSuggestMode())) {
      return rejectedWhenOccupied(AiRequest.Kind.SUGGEST);
    }
    if (changeSetData.hasParsedCommand(CommandSet.REVIEW)
        || Boolean.TRUE.equals(changeSetData.getForcedReview())) {
      return rejectedWhenOccupied(AiRequest.Kind.REVIEW);
    }
    if (hasDirectCommand(changeSetData) || !changeSetData.shouldRequestAiReview()) {
      return AiRequestIntakeDecision.direct();
    }
    return queued(AiRequest.Kind.MESSAGE);
  }

  private static boolean hasDirectCommand(ChangeSetData changeSetData) {
    return ClientCommandBase.DIRECT_COMMANDS.stream().anyMatch(changeSetData::hasParsedCommand);
  }

  private static AiRequestIntakeDecision rejectedWhenOccupied(AiRequest.Kind kind) {
    return AiRequestIntakeDecision.persistent(
        kind, AiRequest.AdmissionPolicy.REJECT_IF_OCCUPIED);
  }

  private static AiRequestIntakeDecision queued(AiRequest.Kind kind) {
    return AiRequestIntakeDecision.persistent(kind, AiRequest.AdmissionPolicy.QUEUE);
  }
}
