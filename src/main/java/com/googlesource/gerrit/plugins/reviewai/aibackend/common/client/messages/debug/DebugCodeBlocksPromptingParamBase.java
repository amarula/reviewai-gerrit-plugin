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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.messages.debug;

import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.CODE_DELIMITER;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.CODE_DELIMITER_BEGIN;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.distanceCodeDelimiter;
import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithNewLine;

public abstract class DebugCodeBlocksPromptingParamBase extends DebugCodeBlocksComposer {
  protected record ScopedPromptingParameter(ReviewScope scope, String title) {}

  protected final Configuration config;
  protected final ChangeSetData changeSetData;
  protected final GerritChange change;
  protected final ICodeContextPolicy codeContextPolicy;
  protected final LinkedHashMap<String, String> promptingParameters = new LinkedHashMap<>();

  public DebugCodeBlocksPromptingParamBase(
      Localizer localizer,
      String titleKey,
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(localizer, titleKey);
    this.config = config;
    this.change = change;
    this.changeSetData = changeSetData;
    this.codeContextPolicy = codeContextPolicy;
  }

  protected abstract void populateAiPromptParameters();

  protected String getScopedDebugCodeBlock(
      ReviewScope reviewScope, List<ScopedPromptingParameter> scopedParameters) {
    populateAiPromptParameters();
    return scopedParameters.stream()
        .filter(parameter -> shouldInclude(reviewScope, parameter.scope()))
        .map(
            parameter ->
                getDelimitedSection(parameter.title(), promptingParameters.get(parameter.title())))
        .collect(Collectors.joining("\n\n"));
  }

  private String getDelimitedSection(String title, String body) {
    return CODE_DELIMITER_BEGIN
        + joinWithNewLine(List.of(title, distanceCodeDelimiter(body)))
        + "\n"
        + CODE_DELIMITER;
  }

  private boolean shouldInclude(ReviewScope requestedScope, ReviewScope scope) {
    return requestedScope == null || requestedScope == scope;
  }
}
