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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The concerns a suggestion run should be fixing, taken from the ledger.
 *
 * <p>A suggestion is written to resolve a concern, so the concern is what it should be given: the
 * problem as it was raised, where it was raised, and the condition that would settle it. Feeding it
 * the wording of a review reply instead leaves the suggestion and the review judging two different
 * texts, which is how an author ends up applying exactly what they were asked for and being told it
 * does not hold.
 *
 * <p>The result is expressed as {@link AiReplyItem}s because that is what the suggestion request
 * and its response already carry, which keeps one correlation mechanism rather than two. Each
 * target carries its concern's id, so a suggestion can be traced back to the concern it answers.
 */
public final class OpenConcerns {
  private OpenConcerns() {}

  /**
   * Statuses that are still asking for something. Everything else has been settled one way or
   * another.
   */
  private static final List<ConcernStatus> OPEN =
      List.of(ConcernStatus.PRESENT, ConcernStatus.UNCERTAIN);

  /**
   * Returns the open concerns as items to fix.
   *
   * @param fileInRevision decides whether a concern's location is still part of the revision, so
   *     this stays independent of how the revision is read
   * @param scope limits the result to patch-set files or to the commit message, or {@code null} for
   *     both
   */
  public static List<AiReplyItem> asSuggestionTargets(
      ReviewConcernLedger ledger, Predicate<String> fileInRevision, ReviewScope scope) {
    if (ledger == null) {
      return List.of();
    }
    ledger.normalize();
    List<AiReplyItem> targets = new ArrayList<>();
    for (ReviewerConcerns reviewerConcerns : ledger.getReviewers()) {
      for (ReviewConcern concern : reviewerConcerns.getConcerns()) {
        if (!OPEN.contains(concern.getStatus())) {
          continue;
        }
        ConcernLocation location = locatedInRevision(concern, fileInRevision);
        if (location == null || !inScope(location.getFilename(), scope)) {
          // No location still in the revision means there is no code here to propose an edit to.
          // The
          // concern may still be open, but a suggestion is not the way to settle it.
          continue;
        }
        targets.add(target(concern, location));
      }
    }
    return targets;
  }

  private static ConcernLocation locatedInRevision(
      ReviewConcern concern, Predicate<String> fileInRevision) {
    for (ConcernLocation location : concern.getLocations()) {
      if (location.getFilename() != null && fileInRevision.test(location.getFilename())) {
        return location;
      }
    }
    return null;
  }

  private static boolean inScope(String filename, ReviewScope scope) {
    if (scope == null) {
      return true;
    }
    boolean commitMessage = filename.endsWith(Settings.GERRIT_COMMIT_MESSAGE_FILENAME);
    return scope == ReviewScope.COMMIT_MESSAGE ? commitMessage : !commitMessage;
  }

  private static AiReplyItem target(ReviewConcern concern, ConcernLocation location) {
    return AiReplyItem.builder()
        // No id: the request and its response are correlated by position, and the client that sends
        // the
        // request numbers its targets. Setting one here would be overwritten there, and a value
        // with two
        // owners is one that can disagree with itself.
        .concernId(concern.getId())
        .filename(location.getFilename())
        .lineNumber(location.getLineNumber())
        .codeSnippet(location.getCodeSnippet())
        .reply(concern.getDescription())
        .build();
  }
}
