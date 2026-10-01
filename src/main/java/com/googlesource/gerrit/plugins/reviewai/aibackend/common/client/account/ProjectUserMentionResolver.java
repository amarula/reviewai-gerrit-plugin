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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account;

import com.google.gerrit.entities.BranchNameKey;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.permissions.PermissionBackend;
import com.google.gerrit.server.permissions.RefPermission;
import com.google.inject.Inject;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProjectUserMentionResolver {
  private static final Pattern USER_MENTION_PATTERN =
      Pattern.compile("(?<![\\w@])@([A-Za-z0-9_](?:[A-Za-z0-9_.-]*[A-Za-z0-9_])?)\\b(?!@)");

  private final AccountCache accountCache;
  private final PermissionBackend permissionBackend;

  @Inject
  public ProjectUserMentionResolver(
      AccountCache accountCache, PermissionBackend permissionBackend) {
    this.accountCache = accountCache;
    this.permissionBackend = permissionBackend;
  }

  public boolean addressesProjectUser(
      String message, String authorUsername, String aiUsername, BranchNameKey branch) {
    if (!message.contains("@")) {
      return false;
    }
    for (String line : message.split("\\R")) {
      if (line.startsWith(">")) {
        continue;
      }
      Matcher mentions = USER_MENTION_PATTERN.matcher(line);
      while (mentions.find()) {
        String username = mentions.group(1);
        if (username.equals(authorUsername) || username.equals(aiUsername)) {
          continue;
        }
        try {
          Optional<AccountState> account = accountCache.getByUsername(username);
          if (account.isEmpty()) {
            log.debug("Mentioned user '{}' was not found", username);
            continue;
          }
          if (!account.orElseThrow().account().isActive()) {
            log.debug("Mentioned user '{}' is inactive", username);
            continue;
          }
          boolean canRead =
              permissionBackend
                  .absentUser(account.orElseThrow().account().id())
                  .ref(branch)
                  .testOrFalse(RefPermission.READ);
          log.debug("Mentioned user '{}' can read {}: {}", username, branch, canRead);
          if (canRead) {
            return true;
          }
        } catch (RuntimeException e) {
          log.debug("Could not check mentioned user '{}'", username, e);
        }
      }
    }
    return false;
  }
}
