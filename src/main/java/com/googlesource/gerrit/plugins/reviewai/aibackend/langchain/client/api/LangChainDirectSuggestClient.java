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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPromptSuggestRequest;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
class LangChainDirectSuggestClient {
  private final LangChainClient client;

  LangChainDirectSuggestClient(LangChainClient client) {
    this.client = client;
  }

  AiResponseContent ask(ChangeSetData changeSetData, GerritChange change, String patchSet)
      throws Exception {
    log.info("Requesting Gerrit suggested edits from existing AI review context");
    LangChainClient.ReviewRequestResult result =
        client.askSingleRequest(
            LangChainSuggestData.suggestion(changeSetData),
            change,
            AiPromptSuggestRequest.forExistingContext(patchSet, changeSetData.getReviewScope()));
    client.setRequestBody(result == null ? null : result.getRequestBody());
    AiResponseContent response = new AiResponseContent("");
    response.setReplies(
        result == null || result.getResponseContent() == null
            ? List.of()
            : prepareSuggestions(result.getResponseContent(), patchSet));
    return response;
  }

  private List<AiReplyItem> prepareSuggestions(AiResponseContent responseContent, String patchSet) {
    List<AiReplyItem> suggestions = new ArrayList<>();
    boolean commitMessageSuggestionAdded = false;
    for (AiReplyItem suggestion : SuggestedEditSupport.responseReplies(responseContent)) {
      boolean commitMessageSuggestion =
          SuggestedEditSupport.isCommitMessageFile(suggestion.getFilename());
      if (commitMessageSuggestion && commitMessageSuggestionAdded) {
        log.warn("Ignoring additional direct AI commit-message suggestion");
        continue;
      }
      suggestion.setId(null);
      suggestion.setScore(null);
      if (commitMessageSuggestion) {
        suggestion.setLineNumber(null);
        suggestion.setCodeSnippet(SuggestedEditSupport.extractCommitMessage(patchSet));
      }
      if (SuggestedEditSupport.hasSuggestionFence(suggestion)
          && (commitMessageSuggestion || SuggestedEditSupport.hasCodeSuggestionTarget(suggestion))) {
        suggestions.add(suggestion);
        commitMessageSuggestionAdded |= commitMessageSuggestion;
      } else {
        log.warn(
            "Ignoring direct AI response that cannot be converted to a Gerrit suggested edit: {}",
            suggestion);
      }
    }
    return suggestions;
  }
}
