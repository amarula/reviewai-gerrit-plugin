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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import static com.googlesource.gerrit.plugins.reviewai.utils.TextUtils.joinWithSpace;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.git.GitRepoFiles;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.prompt.IAiPrompt;
import java.util.ArrayList;
import java.util.List;

/**
 * Appends repository-level project instructions after prompt creation.
 *
 * <p>Project instructions are loaded from files in the target repository. That requires
 * {@link GitRepoFiles}, which in production is backed by Gerrit's {@code GitRepositoryManager}.
 * Keeping that repository-access dependency here lets prompt classes and {@code AiPromptFactory}
 * stay focused on prompt text construction, instead of threading {@link GitRepoFiles} through many
 * prompt constructors that do not need it.
 *
 * <p>The {@link #append(IAiPrompt, GerritChange, String)} guard also preserves the previous
 * behavior: project instructions are appended only for request prompts, not for suggest, router,
 * collector, or specialized-agent prompt types.
 */
public class ProjectInstructionsAppender {
  private final GitRepoFiles gitRepoFiles;

  public ProjectInstructionsAppender(GitRepoFiles gitRepoFiles) {
    this.gitRepoFiles = gitRepoFiles;
  }

  public String append(IAiPrompt prompt, GerritChange change, String systemInstructions) {
    if (!(prompt instanceof AiPromptRequests)
        || gitRepoFiles == null
        || systemInstructions == null) {
      return systemInstructions;
    }
    List<String> instructions = new ArrayList<>();
    instructions.add(systemInstructions);
    new ProjectInstructions(change, gitRepoFiles).addProjectInstructions(instructions);
    return joinWithSpace(instructions);
  }
}
