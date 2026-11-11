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

import dev.langchain4j.skills.ClassPathSkillLoader;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.Skill;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.commonmark.node.Heading;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;

public final class ReviewAgentSkills {
  private static final Map<String, Skill> CACHE = new ConcurrentHashMap<>();
  private static final Parser MARKDOWN =
      Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
  private static final TextContentRenderer HEADING_TEXT = TextContentRenderer.builder().build();

  private ReviewAgentSkills() {}

  public static Skill load(String name) {
    validateName(name);
    return CACHE.computeIfAbsent(name, key -> load(key, ReviewAgentSkills.class.getClassLoader()));
  }

  public static String instructions(String name) {
    return load(name).content();
  }

  public static String section(String name, String title) {
    String content = instructions(name);
    int start = -1;
    // Only top-level headings delimit sections; fenced examples and nested blocks do not.
    for (var node = MARKDOWN.parse(content).getFirstChild(); node != null; node = node.getNext()) {
      if (!(node instanceof Heading heading) || heading.getLevel() != 2) {
        continue;
      }
      var spans = heading.getSourceSpans();
      if (start >= 0) {
        return content.substring(start, spans.getFirst().getInputIndex()).strip();
      }
      if (HEADING_TEXT.render(heading).strip().equals(title)) {
        var lastSpan = spans.getLast();
        start = lastSpan.getInputIndex() + lastSpan.getLength();
      }
    }
    if (start >= 0) {
      return content.substring(start).strip();
    }
    throw new IllegalArgumentException("Missing section " + title + " in skill " + name);
  }

  private static void validateName(String name) {
    if (name == null || !name.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
      throw new IllegalArgumentException("Invalid bundled skill name: " + name);
    }
  }

  static synchronized Skill load(String name, ClassLoader classLoader) {
    validateName(name);
    String directory = "skills/" + name;
    URL resource = classLoader.getResource(directory + "/SKILL.md");
    if (resource == null) {
      throw new IllegalStateException("Missing bundled skill: " + name);
    }
    try {
      URI uri = resource.toURI();
      if ("jar".equals(uri.getScheme())) {
        // The library's classpath loader requires an open JAR filesystem. Resolve the
        // file directly because packaged JARs need not contain directory entries.
        try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
          return FileSystemSkillLoader.loadSkill(fileSystem.getPath("/" + directory));
        } catch (FileSystemAlreadyExistsException e) {
          return FileSystemSkillLoader.loadSkill(Path.of(uri).getParent());
        }
      }
      return ClassPathSkillLoader.loadSkill(directory, classLoader);
    } catch (IOException | URISyntaxException e) {
      throw new IllegalStateException("Unable to load bundled skill: " + name, e);
    }
  }
}
