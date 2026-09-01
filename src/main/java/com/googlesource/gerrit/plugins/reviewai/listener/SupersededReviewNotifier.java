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

import com.google.common.base.Strings;
import com.google.gerrit.extensions.api.changes.NotifyHandling;
import com.google.gerrit.extensions.api.changes.ReviewInput;
import com.google.gerrit.extensions.api.changes.ReviewResult;
import com.google.gerrit.server.util.ManualRequestContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.AiRequest;
import com.googlesource.gerrit.plugins.reviewai.errors.exceptions.GerritReviewException;
import com.googlesource.gerrit.plugins.reviewai.localization.Localizer;
import com.googlesource.gerrit.plugins.reviewai.localization.SystemMessageFormatter;

@Singleton
class SupersededReviewNotifier {
  private final Localizer localizer;

  @Inject
  SupersededReviewNotifier(Localizer localizer) {
    this.localizer = localizer;
  }

  void publish(
      Configuration config,
      GerritChange currentChange,
      AiRequest supersededRequest,
      long newerPatchSetNumber)
      throws Exception {
    Integer olderPatchSetNumber = supersededPatchSetNumber(supersededRequest);
    String message =
        olderPatchSetNumber == null
            ? SystemMessageFormatter.getLocalizedWarningMessage(
                localizer, "message.review.superseded")
            : SystemMessageFormatter.getLocalizedWarningMessage(
                localizer,
                "message.review.patchset.superseded",
                olderPatchSetNumber,
                newerPatchSetNumber);
    ReviewInput reviewInput = ReviewInput.create();
    reviewInput.message(message);
    reviewInput.notify = NotifyHandling.NONE;
    try (ManualRequestContext ignored = config.openRequestContext()) {
      ReviewResult result = currentChange.getChangeApi(config).current().review(reviewInput);
      if (!Strings.isNullOrEmpty(result.error)) {
        throw new GerritReviewException(result.error);
      }
    }
  }

  private static Integer supersededPatchSetNumber(AiRequest request) {
    try {
      return AiRequestDescriptor.fromJson(request.payloadJson()).patchSet().number();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
