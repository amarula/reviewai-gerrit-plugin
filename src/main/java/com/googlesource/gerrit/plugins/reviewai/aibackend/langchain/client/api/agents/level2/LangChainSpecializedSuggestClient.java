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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiResponseContent;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainSuggestClient;

class LangChainSpecializedSuggestClient extends LangChainSuggestClient {
  private final LangChainClient suggestContextClient;
  private final SpecializedSuggestReviewContext reviewContext;

  LangChainSpecializedSuggestClient(
      LangChainClient reviewClient,
      LangChainClient suggestContextClient,
      SpecializedSuggestReviewContext reviewContext) {
    super(reviewClient);
    this.suggestContextClient = suggestContextClient;
    this.reviewContext = reviewContext;
  }

  @Override
  protected boolean hasExistingReviewContext(
      ChangeSetData reviewData, ChangeSetData changeSetData, GerritChange change) {
    return reviewContext.shouldUsePreviousReviewsAsSuggestContext(changeSetData)
        || super.hasExistingReviewContext(reviewData, changeSetData, change);
  }

  @Override
  protected AiResponseContent askExistingReviewContext(
      ChangeSetData changeSetData, GerritChange change, String patchSet) throws Exception {
    if (reviewContext.shouldUsePreviousReviewsAsSuggestContext(changeSetData)) {
      return askExistingReviewContext(
          suggestContextClient,
          changeSetData,
          change,
          reviewContext.appendPreviousReviewsContext(changeSetData, patchSet));
    }
    return super.askExistingReviewContext(changeSetData, change, patchSet);
  }
}
