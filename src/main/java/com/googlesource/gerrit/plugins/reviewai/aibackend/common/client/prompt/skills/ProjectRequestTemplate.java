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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.AiPrompt;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level1.router.AiPromptReviewAgentRouter;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.io.FileNotFoundException;
import lombok.extern.slf4j.Slf4j;

/** Resolves request templates from the target branch without caching project content. */
@Slf4j
public final class ProjectRequestTemplate {
  private final GitRepoFiles gitRepoFiles;

  public ProjectRequestTemplate(GitRepoFiles gitRepoFiles) {
    this.gitRepoFiles = gitRepoFiles;
  }

  /** Configures lazy resolution so only the template actually used by a prompt is loaded. */
  public void configure(IAiPrompt prompt, GerritChange change) {
    if (prompt instanceof AiPrompt aiPrompt) {
      configurePrompt(aiPrompt, change);
    }
  }

  /** Configures the routing prompt, which runs outside the regular prompt interface. */
  public void configureRouting(AiPromptReviewAgentRouter prompt, GerritChange change) {
    configurePrompt(prompt, change);
  }

  private void configurePrompt(AiPrompt prompt, GerritChange change) {
    if (gitRepoFiles != null) {
      prompt.setRequestTemplateResolver((skill, bundled) -> resolve(change, skill, bundled));
    }
  }

  private String resolve(GerritChange change, String skill, String bundled) {
    String path = ".gerrit/skills/" + skill + "/assets/request.md";
    try {
      String template = gitRepoFiles.getFileContent(change, path);
      if (template == null) {
        return bundled;
      }
      validate(template);
      return template.strip();
    } catch (FileNotFoundException e) {
      log.debug("No project request template at {} for {}", path, change.getFullChangeId());
    } catch (IllegalArgumentException e) {
      log.warn(
          "Ignoring invalid project request template at {} for {}: {}",
          path,
          change.getFullChangeId(),
          e.getMessage());
    }
    return bundled;
  }

  private static void validate(String template) {
    int arguments = 0;
    for (int i = 0; i < template.length(); i++) {
      if (template.charAt(i) != '%') {
        continue;
      }
      if (++i == template.length()) {
        throw new IllegalArgumentException("Escape literal percent signs as %%");
      }
      switch (template.charAt(i)) {
        case 's' -> arguments++;
        case '%' -> {
          // Escaped percent signs do not consume the patch-set argument.
        }
        default -> throw new IllegalArgumentException("Only %s and %% are supported");
      }
    }
    if (arguments != 1) {
      throw new IllegalArgumentException("Expected exactly one %s placeholder");
    }
  }
}
