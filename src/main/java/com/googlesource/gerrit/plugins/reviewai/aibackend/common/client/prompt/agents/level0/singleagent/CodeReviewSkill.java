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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent;

import dev.langchain4j.skills.ClassPathSkillLoader;
import dev.langchain4j.skills.FileSystemSkillLoader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;

final class CodeReviewSkill {
  private static final String SKILL_DIRECTORY = "skills/code-review";

  private CodeReviewSkill() {}

  static String instructions() {
    return SkillInstructions.CONTENT;
  }

  static String loadReviewGuidelines(ClassLoader classLoader) {
    URL resource = classLoader.getResource(SKILL_DIRECTORY + "/SKILL.md");
    if (resource == null) {
      throw new IllegalStateException("Missing bundled code-review skill");
    }
    try {
      URI uri = resource.toURI();
      if ("jar".equals(uri.getScheme())) {
        // The library's classpath loader requires an open JAR filesystem. Resolve the
        // file directly because packaged JARs need not contain directory entries.
        try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
          return FileSystemSkillLoader.loadSkill(fileSystem.getPath("/" + SKILL_DIRECTORY))
              .content();
        } catch (FileSystemAlreadyExistsException e) {
          return FileSystemSkillLoader.loadSkill(Path.of(uri).getParent()).content();
        }
      }
      return ClassPathSkillLoader.loadSkill(SKILL_DIRECTORY, classLoader).content();
    } catch (IOException | URISyntaxException e) {
      throw new IllegalStateException("Unable to load bundled code-review skill", e);
    }
  }

  private static final class SkillInstructions {
    private static final String CONTENT =
        loadReviewGuidelines(CodeReviewSkill.class.getClassLoader());
  }
}
