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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.ai.AiReplyItem;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ReviewScope;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.Test;

public class OpenConcernsTest {
  private static final ConcernReviewerId REVIEWER =
      new ConcernReviewerId(ConcernReviewerId.Kind.SINGLE_AGENT, "agent");
  private static final Predicate<String> IN_REVISION = Set.of("A.kt", "/COMMIT_MSG")::contains;

  @Test
  public void turnsAnOpenConcernIntoATargetToFix() {
    ReviewConcern concern = concern("c1", ConcernStatus.PRESENT, "A.kt", 42, "val broken = true");

    List<AiReplyItem> targets =
        OpenConcerns.asSuggestionTargets(ledger(concern), IN_REVISION, null);

    assertEquals(1, targets.size());
    AiReplyItem target = targets.getFirst();
    assertEquals("the problem as it was raised", concern.getDescription(), target.getReply());
    assertEquals("traceable back to the concern", "c1", target.getConcernId());
    assertEquals("A.kt", target.getFilename());
    assertEquals(Integer.valueOf(42), target.getLineNumber());
    assertEquals("val broken = true", target.getCodeSnippet());
  }

  @Test
  public void leavesSettledConcernsAlone() {
    ReviewConcernLedger ledger =
        ledger(
            concern("c1", ConcernStatus.FIXED, "A.kt", 1, "a"),
            concern("c2", ConcernStatus.DISMISSED, "A.kt", 2, "b"),
            concern("c3", ConcernStatus.SKIPPED, "A.kt", 3, "c"));

    assertTrue(OpenConcerns.asSuggestionTargets(ledger, IN_REVISION, null).isEmpty());
  }

  @Test
  public void includesAConcernTheReviewerWasUnsureAbout() {
    // "I cannot tell whether this is fixed" is exactly a case where a proposed fix settles it.
    ReviewConcernLedger ledger = ledger(concern("c1", ConcernStatus.UNCERTAIN, "A.kt", 1, "a"));

    assertEquals(1, OpenConcerns.asSuggestionTargets(ledger, IN_REVISION, null).size());
  }

  @Test
  public void skipsAConcernWhoseCodeHasLeftTheRevision() {
    ReviewConcernLedger ledger = ledger(concern("c1", ConcernStatus.PRESENT, "Gone.kt", 1, "a"));

    assertTrue(OpenConcerns.asSuggestionTargets(ledger, IN_REVISION, null).isEmpty());
  }

  @Test
  public void usesALocationThatIsStillInTheRevision() {
    ReviewConcern concern = concern("c1", ConcernStatus.PRESENT, "Gone.kt", 1, "gone");
    concern.setLocations(
        List.of(location("Gone.kt", 1, "gone"), location("A.kt", 7, "still here")));

    AiReplyItem target =
        OpenConcerns.asSuggestionTargets(ledger(concern), IN_REVISION, null).getFirst();

    assertEquals("A.kt", target.getFilename());
    assertEquals(Integer.valueOf(7), target.getLineNumber());
  }

  @Test
  public void scopesToPatchSetFilesOrToTheCommitMessage() {
    ReviewConcernLedger ledger =
        ledger(
            concern("c1", ConcernStatus.PRESENT, "A.kt", 1, "code"),
            concern("c2", ConcernStatus.PRESENT, "/COMMIT_MSG", 1, "message"));

    assertEquals(
        List.of("A.kt"),
        filenames(OpenConcerns.asSuggestionTargets(ledger, IN_REVISION, ReviewScope.PATCHSET)));
    assertEquals(
        List.of("/COMMIT_MSG"),
        filenames(
            OpenConcerns.asSuggestionTargets(ledger, IN_REVISION, ReviewScope.COMMIT_MESSAGE)));
    assertEquals(
        "no scope means both",
        2,
        OpenConcerns.asSuggestionTargets(ledger, IN_REVISION, null).size());
  }

  @Test
  public void nullLedgerHasNothingToFix() {
    assertTrue(OpenConcerns.asSuggestionTargets(null, IN_REVISION, null).isEmpty());
  }

  @Test
  public void anEmptyLedgerHasNothingToFix() {
    assertTrue(
        OpenConcerns.asSuggestionTargets(new ReviewConcernLedger(), IN_REVISION, null).isEmpty());
  }

  private static List<String> filenames(List<AiReplyItem> targets) {
    return targets.stream().map(AiReplyItem::getFilename).toList();
  }

  private static ReviewConcernLedger ledger(ReviewConcern... concerns) {
    ReviewerConcerns reviewerConcerns = new ReviewerConcerns();
    reviewerConcerns.setReviewer(REVIEWER);
    reviewerConcerns.setConcerns(List.of(concerns));
    ReviewConcernLedger ledger = new ReviewConcernLedger();
    ledger.setReviewers(List.of(reviewerConcerns));
    return ledger;
  }

  private static ReviewConcern concern(
      String id, ConcernStatus status, String filename, int line, String snippet) {
    ReviewConcern concern = new ReviewConcern();
    concern.setId(id);
    concern.setStatus(status);
    concern.setDescription("the problem with " + id);
    concern.setLocations(List.of(location(filename, line, snippet)));
    return concern;
  }

  private static ConcernLocation location(String filename, int line, String snippet) {
    ConcernLocation location = new ConcernLocation();
    location.setFilename(filename);
    location.setLineNumber(line);
    location.setCodeSnippet(snippet);
    return location;
  }
}
