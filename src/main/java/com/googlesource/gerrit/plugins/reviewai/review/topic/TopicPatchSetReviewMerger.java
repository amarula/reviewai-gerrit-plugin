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

package com.googlesource.gerrit.plugins.reviewai.review.topic;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TopicPatchSetReviewMerger {
  private static final String TOPIC_PATCH_SET_PREFIX = "reviewai-topic-change-";

  public TopicReviewPatchSet patchSet(GerritChange change, int index, String patchSet) {
    return new TopicReviewPatchSet(change, TOPIC_PATCH_SET_PREFIX + index + "/", patchSet);
  }

  public String buildMergedPatchSet(List<TopicReviewPatchSet> patchSets) {
    List<String> parts = new ArrayList<>();
    parts.add(
        "Review these Gerrit patch sets as one topic push. Each diff filename is prefixed with "
            + "a ReviewAI origin path. Use that exact prefixed filename in every inline reply so "
            + "the review can be published back to the original patch set.");
    for (TopicReviewPatchSet patchSet : patchSets) {
      GerritChange change = patchSet.change();
      parts.add(
          String.join(
              "\n",
              "ReviewAI origin: " + patchSet.prefix(),
              "Gerrit change: " + change.getFullChangeId(),
              "Patch set: " + change.getPatchSetAttribute().map(ps -> ps.number).orElse(null),
              prefixPatchFilenames(patchSet.patchSet(), patchSet.prefix())));
    }
    return String.join("\n\n", parts);
  }

  private String prefixPatchFilenames(String patchSet, String prefix) {
    return String.join(
        "\n",
        Arrays.stream(patchSet.split("\n", -1))
            .map(line -> prefixPatchLine(line, prefix))
            .toList());
  }

  private String prefixPatchLine(String line, String prefix) {
    if (line.startsWith("diff --git a/")) {
      String[] parts = line.split(" ", 4);
      if (parts.length == 4 && parts[2].startsWith("a/") && parts[3].startsWith("b/")) {
        return String.join(
            " ",
            parts[0],
            parts[1],
            "a/" + prefix + parts[2].substring(2),
            "b/" + prefix + parts[3].substring(2));
      }
    }
    if (line.startsWith("--- a/")) {
      return "--- a/" + prefix + line.substring("--- a/".length());
    }
    if (line.startsWith("+++ b/")) {
      return "+++ b/" + prefix + line.substring("+++ b/".length());
    }
    if (line.startsWith("rename from ")) {
      return "rename from " + prefix + line.substring("rename from ".length());
    }
    if (line.startsWith("rename to ")) {
      return "rename to " + prefix + line.substring("rename to ".length());
    }
    if (line.startsWith("copy from ")) {
      return "copy from " + prefix + line.substring("copy from ".length());
    }
    if (line.startsWith("copy to ")) {
      return "copy to " + prefix + line.substring("copy to ".length());
    }
    return line;
  }
}
