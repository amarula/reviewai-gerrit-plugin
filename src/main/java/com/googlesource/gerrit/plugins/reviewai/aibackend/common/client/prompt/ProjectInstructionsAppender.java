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
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.AiPromptSpecializedReviewAgent;
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
 * <p>General project instructions apply only to request prompts. Project skill guidance applies to
 * single-agent reviews and selected patchset specialists; other stages retain their bundled skills.
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
    if (prompt instanceof AiPromptSpecializedReviewAgent specialist) {
      return specialist
          .getSpecialistSkillName()
          .map(name -> appendSkill(name, change, systemInstructions))
          .orElse(systemInstructions);
    }
    // Review subclasses represent other stages and must not inherit code-review customization.
    if (prompt != null && prompt.getClass() == AiPromptReview.class) {
      return appendSkill("code-review", change, systemInstructions);
    }
    if (!(prompt instanceof AiPromptRequests)) {
      return systemInstructions;
    }
    List<String> instructions = new ArrayList<>();
    instructions.add(systemInstructions);
    new ProjectInstructions(change, gitRepoFiles).addProjectInstructions(instructions);
    return joinWithSpace(instructions);
  }

  private String appendSkill(String name, GerritChange change, String systemInstructions) {
    return new ProjectSkillInstructions(gitRepoFiles)
        .load(change, name)
        .map(
            guidance ->
                systemInstructions
                    + "\n\n"
                    + AiPromptSections.buildSection("Project " + name + " guidance", guidance))
        .orElse(systemInstructions);
  }
}
