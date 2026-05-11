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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.memory;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient.ReviewAssistantStages;
import java.util.Locale;
import java.util.Objects;

public final class LangChainMemoryId {
  private static final String DEFAULT_SCOPE = "review";
  private static final String REQUESTS_SCOPE = "requests";

  private final String changeId;
  private final int patchSet;
  private final String scope;

  public LangChainMemoryId(String changeId, int patchSet, String scope) {
    this.changeId = changeId;
    this.patchSet = patchSet;
    this.scope = scope;
  }

  public String getChangeId() {
    return changeId;
  }

  public int getPatchSet() {
    return patchSet;
  }

  public String getScope() {
    return scope;
  }

  public static LangChainMemoryId from(ChangeSetData changeSetData, GerritChange change) {
    return new LangChainMemoryId(
        change.getFullChangeId(), getPatchSetNumber(change), getMemoryScope(changeSetData, change));
  }

  public static int getPatchSetNumber(GerritChange change) {
    return change.getPatchSetAttribute().map(attribute -> attribute.number).orElse(0);
  }

  private static String getMemoryScope(ChangeSetData changeSetData, GerritChange change) {
    if (Boolean.TRUE.equals(change.getIsCommentEvent())) {
      return REQUESTS_SCOPE;
    }
    ReviewAssistantStages assistantStage = changeSetData.getReviewAssistantStage();
    if (assistantStage == null) {
      return DEFAULT_SCOPE;
    }
    return assistantStage.name().toLowerCase(Locale.ROOT);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof LangChainMemoryId that)) {
      return false;
    }
    return patchSet == that.patchSet
        && Objects.equals(changeId, that.changeId)
        && Objects.equals(scope, that.scope);
  }

  @Override
  public int hashCode() {
    return Objects.hash(changeId, patchSet, scope);
  }

  @Override
  public String toString() {
    return String.format("%s:%d:%s", changeId, patchSet, scope);
  }
}
