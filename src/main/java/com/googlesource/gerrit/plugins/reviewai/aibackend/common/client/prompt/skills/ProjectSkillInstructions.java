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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import java.io.FileNotFoundException;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.front.matter.YamlFrontMatterBlock;
import org.commonmark.ext.front.matter.YamlFrontMatterExtension;
import org.commonmark.ext.front.matter.YamlFrontMatterVisitor;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

/** Reads additive project guidance without caching repository content as a bundled skill. */
@Slf4j
public final class ProjectSkillInstructions {
  private static final String CODE_REVIEW_PATH = ".gerrit/skills/code-review/SKILL.md";
  // Use the same front matter parser as LangChain4j's bundled skill loaders.
  private static final Parser MARKDOWN =
      Parser.builder()
          .extensions(List.of(YamlFrontMatterExtension.create()))
          .includeSourceSpans(IncludeSourceSpans.BLOCKS)
          .build();

  private final GitRepoFiles gitRepoFiles;

  public ProjectSkillInstructions(GitRepoFiles gitRepoFiles) {
    this.gitRepoFiles = gitRepoFiles;
  }

  public Optional<String> loadCodeReview(GerritChange change) {
    try {
      String markdown = gitRepoFiles.getFileContent(change, CODE_REVIEW_PATH);
      if (markdown == null) {
        return Optional.empty();
      }
      var document = MARKDOWN.parse(markdown);
      if (!(document.getFirstChild() instanceof YamlFrontMatterBlock frontMatter)) {
        throw new IllegalArgumentException("Missing YAML front matter");
      }
      var visitor = new YamlFrontMatterVisitor();
      frontMatter.accept(visitor);
      var metadata = visitor.getData();
      if (!List.of("code-review").equals(metadata.get("name"))) {
        throw new IllegalArgumentException("Expected name: code-review");
      }
      var description = metadata.getOrDefault("description", List.of());
      if (description.size() != 1 || description.getFirst().isBlank()) {
        throw new IllegalArgumentException("Expected a nonempty description");
      }
      var body = frontMatter.getNext();
      if (body == null) {
        throw new IllegalArgumentException("Missing skill instructions");
      }
      int start = body.getSourceSpans().getFirst().getInputIndex();
      return Optional.of(markdown.substring(start).strip());
    } catch (FileNotFoundException e) {
      log.debug("No project skill at {} for {}", CODE_REVIEW_PATH, change.getFullChangeId());
    } catch (IllegalArgumentException e) {
      log.warn(
          "Ignoring invalid project skill at {} for {}: {}",
          CODE_REVIEW_PATH,
          change.getFullChangeId(),
          e.getMessage());
    }
    return Optional.empty();
  }
}
