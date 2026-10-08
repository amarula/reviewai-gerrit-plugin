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

package com.googlesource.gerrit.plugins.reviewai.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AddedCodeTest {

  /** The shape of the reported case: a one-line lookup replaced by a three-line retry. */
  private static final String RETRY =
      """
      val isKeyLoadable = runCatching { loadKey(masterKeyAlias) != null }
          .recoverCatching { loadKey(masterKeyAlias) != null }
          .getOrDefault(false)
      """;

  private static final String APPLIED_RETRY =
      """
      diff --git a/FirebaseAuthStorageRepair.kt b/FirebaseAuthStorageRepair.kt
      index 1111111..2222222 100644
      --- a/FirebaseAuthStorageRepair.kt
      +++ b/FirebaseAuthStorageRepair.kt
      @@ -60,7 +60,9 @@ object FirebaseAuthStorageRepair {
             val alias = masterKeyAlias
      -      val isKeyLoadable = runCatching { loadKey(alias) != null }.getOrDefault(false)
      +      val isKeyLoadable = runCatching { loadKey(masterKeyAlias) != null }
      +          .recoverCatching { loadKey(masterKeyAlias) != null }
      +          .getOrDefault(false)
             if (isKeyLoadable) return false
      """;

  @Test
  public void findsTheAppliedBlock() {
    assertTrue(AddedCode.appearsIn(APPLIED_RETRY, "FirebaseAuthStorageRepair.kt", RETRY));
  }

  @Test
  public void findsABlockTheAuthorReindented() {
    // The author's editor is not the model's, and the fix is the same fix.
    String reindented =
        """
            val isKeyLoadable = runCatching { loadKey(masterKeyAlias) != null }
                .recoverCatching { loadKey(masterKeyAlias) != null }
                .getOrDefault(false)
        """;

    assertTrue(AddedCode.appearsIn(APPLIED_RETRY, "FirebaseAuthStorageRepair.kt", reindented));
  }

  @Test
  public void findsABlockWhoseLinesWerePartlyUnchanged() {
    // Gerrit applies a suggestion to a range: a line of the replacement identical to the original
    // stays
    // context, so a search over added lines alone would miss it. The middle line here is context.
    String patch =
        """
        diff --git a/A.kt b/A.kt
        --- a/A.kt
        +++ b/A.kt
        @@ -1,3 +1,4 @@
         fun load(): Boolean {
        -    return false
        +    val key = readKey()
        +    return key != null
         }
        """;

    assertTrue(
        AddedCode.appearsIn(patch, "A.kt", "fun load(): Boolean {\n    val key = readKey()"));
  }

  @Test
  public void doesNotFindABlockThatWasNotApplied() {
    assertFalse(
        AddedCode.appearsIn(APPLIED_RETRY, "FirebaseAuthStorageRepair.kt", "val other = true"));
  }

  @Test
  public void doesNotMatchRemovedLines() {
    // The line is on the old side only, so the revision no longer holds it.
    String patch =
        """
        diff --git a/A.kt b/A.kt
        --- a/A.kt
        +++ b/A.kt
        @@ -1,2 +1,2 @@
        -    val isKeyLoadable = runCatching { loadKey(masterKeyAlias) != null }
        +    val isKeyLoadable = false
        """;

    assertFalse(
        AddedCode.appearsIn(
            patch, "A.kt", "val isKeyLoadable = runCatching { loadKey(masterKeyAlias) != null }"));
  }

  @Test
  public void doesNotMatchAcrossFileBoundaries() {
    // Both files are in the delta, but the block spans them, which no author could have written.
    String patch =
        """
        diff --git a/A.kt b/A.kt
        --- a/A.kt
        +++ b/A.kt
        @@ -1,1 +1,1 @@
        +    val first = 1
        diff --git a/B.kt b/B.kt
        --- a/B.kt
        +++ b/B.kt
        @@ -1,1 +1,1 @@
        +    val second = 2
        """;

    assertFalse(AddedCode.appearsIn(patch, "A.kt", "val first = 1\nval second = 2"));
  }

  @Test
  public void looksInEveryFileWhenNoneIsNamed() {
    assertTrue(AddedCode.appearsIn(APPLIED_RETRY, null, RETRY));
    assertTrue(AddedCode.appearsIn(APPLIED_RETRY, "", RETRY));
  }

  @Test
  public void looksOnlyInTheNamedFileWhenOneIs() {
    assertFalse(AddedCode.appearsIn(APPLIED_RETRY, "SomeOtherFile.kt", RETRY));
  }

  @Test
  public void doesNotMatchWhenTheFileIsNotInTheDelta() {
    String patch =
        """
        diff --git a/Unrelated.kt b/Unrelated.kt
        --- a/Unrelated.kt
        +++ b/Unrelated.kt
        @@ -1,1 +1,1 @@
        +    val unrelated = true
        """;

    assertFalse(AddedCode.appearsIn(patch, "FirebaseAuthStorageRepair.kt", RETRY));
  }

  @Test
  public void toleratesADifferentRenderingOfTheSameCode() {
    String differentlySpaced =
        "val isKeyLoadable  =  runCatching { loadKey(masterKeyAlias) != null }\n"
            + "    .recoverCatching { loadKey(masterKeyAlias) != null }\n"
            + "    .getOrDefault(false)";

    assertTrue(
        AddedCode.appearsIn(APPLIED_RETRY, "FirebaseAuthStorageRepair.kt", differentlySpaced));
  }

  @Test
  public void neverMatchesAnEmptyOrMissingBlock() {
    assertFalse(AddedCode.appearsIn(APPLIED_RETRY, "FirebaseAuthStorageRepair.kt", ""));
    assertFalse(AddedCode.appearsIn(APPLIED_RETRY, "FirebaseAuthStorageRepair.kt", null));
    assertFalse(AddedCode.appearsIn(APPLIED_RETRY, "FirebaseAuthStorageRepair.kt", "   \n  "));
  }

  @Test
  public void neverMatchesAgainstNoPatch() {
    assertFalse(AddedCode.appearsIn(null, "A.kt", "val a = 1"));
    assertFalse(AddedCode.appearsIn("", "A.kt", "val a = 1"));
  }

  @Test
  public void doesNotCountTheOldFileHeaderAsContent() {
    // --- a/... and +++ b/... sit before the first hunk and are headers, not lines of the file.
    String patch =
        """
        diff --git a/A.kt b/A.kt
        --- a/A.kt
        +++ b/A.kt
        @@ -1,1 +1,1 @@
        +    val applied = true
        """;

    assertFalse(AddedCode.appearsIn(patch, "A.kt", "--- a/A.kt"));
    assertFalse(AddedCode.appearsIn(patch, "A.kt", "+++ b/A.kt"));
  }

  @Test
  public void readsACombinedDiffWithoutLeavingStrayMarkers() {
    String patch =
        """
        diff --cc A.kt
        index 1111111,2222222..3333333
        --- a/A.kt
        +++ b/A.kt
        @@@ -1,1 -1,1 +1,1 @@@
        ++    val merged = true
        """;

    assertTrue(AddedCode.appearsIn(patch, "A.kt", "val merged = true"));
  }
}
