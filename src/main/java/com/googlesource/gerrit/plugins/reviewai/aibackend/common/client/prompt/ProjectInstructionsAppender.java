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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithSpace;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level0.singleagent.AiPromptReview;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.skills.ProjectSkillInstructions;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.util.ArrayList;
import java.util.List;

/**
 * Appends repository-level project instructions after prompt creation.
 *
 * <p>Project instructions are loaded from files in the target repository. That requires {@link
 * GitRepoFiles}, which in production is backed by Gerrit's {@code GitRepositoryManager}. Keeping
 * that repository-access dependency here lets prompt classes and {@code AiPromptFactory} stay
 * focused on prompt text construction, instead of threading {@link GitRepoFiles} through many
 * prompt constructors that do not need it.
 *
 * <p>General project instructions apply only to request prompts. Project code-review skill guidance
 * applies only to single-agent reviews; other review stages retain their bundled skills.
 */
public class ProjectInstructionsAppender {
  private final GitRepoFiles gitRepoFiles;

  public ProjectInstructionsAppender(GitRepoFiles gitRepoFiles) {
    this.gitRepoFiles = gitRepoFiles;
  }

  public String append(IAiPrompt prompt, GerritChange change, String systemInstructions) {
    if (gitRepoFiles == null || systemInstructions == null) {
      return systemInstructions;
    }
    // Review subclasses represent other stages and must not inherit this skill's customization.
    if (prompt != null && prompt.getClass() == AiPromptReview.class) {
      return new ProjectSkillInstructions(gitRepoFiles)
          .loadCodeReview(change)
          .map(
              guidance ->
                  systemInstructions
                      + "\n\n"
                      + AiPromptSections.buildSection("Project code-review guidance", guidance))
          .orElse(systemInstructions);
    }
    if (!(prompt instanceof AiPromptRequests)) {
      return systemInstructions;
    }
    List<String> instructions = new ArrayList<>();
    instructions.add(systemInstructions);
    new ProjectInstructions(change, gitRepoFiles).addProjectInstructions(instructions);
    return joinWithSpace(instructions);
  }
}
