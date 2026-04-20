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

package com.googlesource.gerrit.plugins.reviewai.web;

import com.google.gerrit.entities.AccessSection;
import com.google.gerrit.entities.Permission;
import com.google.gerrit.entities.PermissionRule;
import com.google.gerrit.entities.Project;
import com.google.gerrit.extensions.restapi.AuthException;
import com.google.gerrit.server.CurrentUser;
import com.google.gerrit.server.change.ChangeResource;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.SectionMatcher;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Singleton
@Slf4j
public class AiReviewPermission {
  static final String AI_REVIEW_ACCESS_PERMISSION = "aiReview";

  private final ProjectCache projectCache;

  @Inject
  AiReviewPermission(ProjectCache projectCache) {
    this.projectCache = projectCache;
  }

  public Boolean canAiReview(ChangeResource resource) {
    return !isAiReviewExplicitlyDisallowed(
        resource.getProject(), resource.getChange().getDest().branch(), resource.getUser());
  }

  public void checkCanAiReview(ChangeResource resource) throws AuthException {
    if (Boolean.FALSE.equals(canAiReview(resource))) {
      throw new AuthException("AI review is not allowed for this change");
    }
  }

  public boolean isAiReviewExplicitlyDisallowed(Project.NameKey projectNameKey, String refName) {
    return isAiReviewExplicitlyDisallowed(projectNameKey, refName, null);
  }

  public boolean isAiReviewExplicitlyDisallowed(
      Project.NameKey projectNameKey, String refName, CurrentUser user) {
    try {
      return projectCache
          .get(projectNameKey)
          .map(
              projectState ->
                  projectState.getAllSections().stream()
                      .filter(sectionMatcher -> matchesAny(sectionMatcher, refName, user))
                      .map(SectionMatcher::getSection)
                      .map(this::getAiReviewPermission)
                      .filter(permission -> permission != null)
                      .flatMap(permission -> permission.getRules().stream())
                      .anyMatch(rule -> isDisallowRuleForUser(rule, user)))
          .orElse(false);
    } catch (RuntimeException e) {
      log.warn(
          "Failed to inspect AI review access rules for project {} and ref {}",
          projectNameKey,
          refName,
          e);
      return false;
    }
  }

  private boolean isDisallowRuleForUser(PermissionRule rule, CurrentUser user) {
    return (rule.isDeny() || rule.isBlock()) && appliesToUser(rule, user);
  }

  private boolean appliesToUser(PermissionRule rule, CurrentUser user) {
    return user == null || user.getEffectiveGroups().contains(rule.getGroup().getUUID());
  }

  private Permission getAiReviewPermission(AccessSection section) {
    Permission permission = section.getPermission(AI_REVIEW_ACCESS_PERMISSION);
    if (permission != null) {
      return permission;
    }
    return section.getPermissions().stream()
        .filter(candidate -> isAiReviewPermissionName(candidate.getName()))
        .findFirst()
        .orElse(null);
  }

  private boolean isAiReviewPermissionName(String permissionName) {
    return AI_REVIEW_ACCESS_PERMISSION.equalsIgnoreCase(permissionName)
        || permissionName.endsWith("~" + AI_REVIEW_ACCESS_PERMISSION);
  }

  private boolean matchesAny(SectionMatcher sectionMatcher, String refName, CurrentUser user) {
    return refNameCandidates(refName).stream()
        .anyMatch(candidate -> matches(sectionMatcher, candidate, user));
  }

  private boolean matches(SectionMatcher sectionMatcher, String refName, CurrentUser user) {
    try {
      return sectionMatcher.match(refName, user);
    } catch (RuntimeException e) {
      return user == null && simpleRefMatch(sectionMatcher.getSection().getName(), refName);
    }
  }

  static Set<String> refNameCandidates(String refName) {
    if (refName == null || refName.isBlank()) {
      return Set.of();
    }
    if (refName.startsWith("refs/heads/")) {
      return Set.of(refName, refName.substring("refs/heads/".length()));
    }
    if (refName.startsWith("refs/")) {
      return Set.of(refName);
    }
    return Set.of(refName, "refs/heads/" + refName);
  }

  private boolean simpleRefMatch(String sectionName, String refName) {
    if (AccessSection.ALL.equals(sectionName) || sectionName.equals(refName)) {
      return true;
    }
    if (sectionName.endsWith("/*")) {
      return refName.startsWith(sectionName.substring(0, sectionName.length() - 1));
    }
    if (sectionName.startsWith(AccessSection.REGEX_PREFIX)) {
      try {
        return Pattern.compile(sectionName.substring(AccessSection.REGEX_PREFIX.length()))
            .matcher(refName)
            .matches();
      } catch (PatternSyntaxException e) {
        return false;
      }
    }
    return false;
  }

}
