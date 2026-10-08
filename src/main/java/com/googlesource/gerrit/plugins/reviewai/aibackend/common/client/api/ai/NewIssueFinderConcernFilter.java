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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.ai;

import static com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritClientPatchSetHelper.extractFilesFromPatch;
import static com.googlesource.gerrit.plugins.reviewai.utils.GerritUtils.isGerritNonFilePath;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernLocation;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernStatus;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcern;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import java.util.List;

/** Selects known concerns that still apply to the change for the New Issue Finder. */
public final class NewIssueFinderConcernFilter {
  private NewIssueFinderConcernFilter() {}

  public static ReviewerConcerns filter(ReviewerConcerns reviewedConcerns, String fullPatchSet) {
    reviewedConcerns.normalize();
    List<String> currentFiles =
        fullPatchSet == null ? List.of() : extractFilesFromPatch(fullPatchSet);
    List<ReviewConcern> includedConcerns =
        reviewedConcerns.getConcerns().stream()
            .filter(
                concern ->
                    concern.getStatus() != ConcernStatus.DETACHED
                        && (currentFiles.isEmpty()
                            || concern.getLocations().isEmpty()
                            || concern.getLocations().stream()
                                .map(ConcernLocation::getFilename)
                                .anyMatch(
                                    filename ->
                                        filename != null
                                            && (isGerritNonFilePath(filename)
                                                || currentFiles.stream()
                                                    .anyMatch(path -> path.contains(filename))))))
            .toList();
    ReviewerConcerns finderConcerns = reviewedConcerns;
    if (includedConcerns.size() != reviewedConcerns.getConcerns().size()) {
      finderConcerns = new ReviewerConcerns();
      finderConcerns.setReviewer(reviewedConcerns.getReviewer());
      finderConcerns.setConcerns(includedConcerns);
    }
    return finderConcerns;
  }
}
