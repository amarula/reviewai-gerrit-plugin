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

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class DiffStatsTest {

  private static final String ONE_FILE_ONE_ADDED_LINE =
      """
      diff --git a/A.java b/A.java
      index 0000000..1111111 100644
      --- a/A.java
      +++ b/A.java
      @@ -1,3 +1,4 @@
       context before
      +added
       context after
      """;

  @Test
  public void countsAddedAndRemovedLines() {
    String patch =
        """
        diff --git a/A.java b/A.java
        --- a/A.java
        +++ b/A.java
        @@ -1,2 +1,2 @@
        -old line
        +new line
        """;

    assertEquals(2, DiffStats.changedLines(patch));
  }

  @Test
  public void doesNotCountFileHeaders() {
    // Five lines of scaffolding for one changed line. Counting these is what made a change touching
    // many files look far larger than it was.
    assertEquals(1, DiffStats.changedLines(ONE_FILE_ONE_ADDED_LINE));
  }

  @Test
  public void countsAnAddedLineWhoseContentStartsWithPluses() {
    // "+++" is a file header outside a hunk and ordinary content inside one, so the count has to be
    // driven by position rather than by the first characters of the line.
    String patch =
        """
        diff --git a/A.java b/A.java
        --- a/A.java
        +++ b/A.java
        @@ -1,2 +1,3 @@
         context
        ++++ a line whose content begins with two plus signs
        """;

    assertEquals(1, DiffStats.changedLines(patch));
  }

  @Test
  public void countsAcrossMultipleFilesAndHunks() {
    String patch =
        """
        diff --git a/A.java b/A.java
        --- a/A.java
        +++ b/A.java
        @@ -1,2 +1,3 @@
         context
        +added in A
        diff --git a/B.java b/B.java
        --- a/B.java
        +++ b/B.java
        @@ -10,3 +10,3 @@
        -removed in B
        +added in B
        @@ -20,1 +20,2 @@
        +added in B again
        """;

    assertEquals(4, DiffStats.changedLines(patch));
  }

  @Test
  public void ignoresTheNoNewlineMarker() {
    String patch =
        """
        diff --git a/A.java b/A.java
        --- a/A.java
        +++ b/A.java
        @@ -1 +1 @@
        -old
        +new
        \\ No newline at end of file
        """;

    assertEquals(2, DiffStats.changedLines(patch));
  }

  @Test
  public void countsNothingWithoutAHunk() {
    // Context is not changed lines, and headers are not either, so a file with neither has none.
    String contextOnly =
        """
        diff --git a/A.java b/A.java
        --- a/A.java
        +++ b/A.java
        @@ -1,3 +1,3 @@
         one
         two
         three
        """;
    String renameOnly =
        """
        diff --git a/A.java b/B.java
        similarity index 100%
        rename from A.java
        rename to B.java
        """;
    String binary =
        """
        diff --git a/image.png b/image.png
        index 0000000..1111111 100644
        Binary files a/image.png and b/image.png differ
        """;

    assertEquals(0, DiffStats.changedLines(contextOnly));
    assertEquals(0, DiffStats.changedLines(renameOnly));
    assertEquals(0, DiffStats.changedLines(binary));
  }

  @Test
  public void contextLinesDoNotChangeTheCount() {
    // patchContextLines is not a lever on the size of a change, and pinning this keeps it from
    // becoming one again.
    String withNoContext =
        """
        diff --git a/A.java b/A.java
        --- a/A.java
        +++ b/A.java
        @@ -1 +1 @@
        -old
        +new
        """;
    String withTenLinesOfContext =
        """
        diff --git a/A.java b/A.java
        --- a/A.java
        +++ b/A.java
        @@ -1,21 +1,21 @@
         context 1
         context 2
         context 3
         context 4
         context 5
         context 6
         context 7
         context 8
         context 9
         context 10
        -old
        +new
         context 11
         context 12
         context 13
         context 14
         context 15
         context 16
         context 17
         context 18
         context 19
         context 20
        """;

    assertEquals(
        DiffStats.changedLines(withNoContext), DiffStats.changedLines(withTenLinesOfContext));
    assertEquals(2, DiffStats.changedLines(withTenLinesOfContext));
  }

  @Test
  public void handlesEmptyInput() {
    assertEquals(0, DiffStats.changedLines(null));
    assertEquals(0, DiffStats.changedLines(""));
  }
}
