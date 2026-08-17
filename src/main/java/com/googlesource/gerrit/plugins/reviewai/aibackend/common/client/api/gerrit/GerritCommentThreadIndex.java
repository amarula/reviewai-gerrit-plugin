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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/** Indexes Gerrit comments by their exact {@code inReplyTo} lineage. */
public class GerritCommentThreadIndex {
  private static final Comparator<GerritComment> COMMENT_ORDER =
      Comparator.comparing(
              GerritCommentThreadIndex::timestamp,
              Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(GerritComment::getId, Comparator.nullsLast(Comparator.naturalOrder()));

  private final Map<String, GerritComment> commentsById = new HashMap<>();
  private final Map<String, List<GerritComment>> childrenByParentId = new HashMap<>();

  public GerritCommentThreadIndex(Collection<GerritComment> comments) {
    for (GerritComment comment : comments) {
      if (comment != null && comment.getId() != null) {
        commentsById.put(comment.getId(), comment);
      }
    }
    for (GerritComment comment : commentsById.values()) {
      if (comment.getInReplyTo() != null) {
        childrenByParentId
            .computeIfAbsent(comment.getInReplyTo(), ignored -> new ArrayList<>())
            .add(comment);
      }
    }
    childrenByParentId.values().forEach(children -> children.sort(COMMENT_ORDER));
  }

  public Optional<GerritComment> parentOf(GerritComment comment) {
    if (comment == null || comment.getInReplyTo() == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(commentsById.get(comment.getInReplyTo()));
  }

  /** Returns the known lineage from its root to {@code comment}, inclusive. */
  public List<GerritComment> lineage(GerritComment comment) {
    Deque<GerritComment> lineage = new ArrayDeque<>();
    Set<String> visitedIds = new HashSet<>();
    GerritComment current = comment;
    while (current != null && visitedIds.add(current.getId())) {
      lineage.addFirst(current);
      current = parentOf(current).orElse(null);
    }
    return List.copyOf(lineage);
  }

  /** Returns the known root, or an empty result when the lineage has a cycle. */
  public Optional<GerritComment> rootOf(GerritComment comment) {
    Set<String> visitedIds = new HashSet<>();
    GerritComment current = comment;
    while (current != null) {
      if (!visitedIds.add(current.getId())) {
        return Optional.empty();
      }
      Optional<GerritComment> parent = parentOf(current);
      if (parent.isEmpty()) {
        return Optional.of(current);
      }
      current = parent.get();
    }
    return Optional.empty();
  }

  /** Finds the nearest matching ancestor, excluding {@code comment} itself. */
  public Optional<GerritComment> nearestAncestor(
      GerritComment comment, Predicate<GerritComment> predicate) {
    Set<String> visitedIds = new HashSet<>();
    GerritComment current = comment;
    if (current != null) {
      visitedIds.add(current.getId());
    }
    current = parentOf(current).orElse(null);
    while (current != null && visitedIds.add(current.getId())) {
      if (predicate.test(current)) {
        return Optional.of(current);
      }
      current = parentOf(current).orElse(null);
    }
    return Optional.empty();
  }

  /** Returns direct children only, in Gerrit timestamp and comment ID order. */
  public List<GerritComment> childrenOf(GerritComment comment) {
    if (comment == null || comment.getId() == null) {
      return List.of();
    }
    return List.copyOf(childrenByParentId.getOrDefault(comment.getId(), List.of()));
  }

  private static String timestamp(GerritComment comment) {
    return comment.getUpdated() != null ? comment.getUpdated() : comment.getDate();
  }
}
