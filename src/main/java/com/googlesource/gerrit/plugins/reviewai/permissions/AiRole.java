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

package com.googlesource.gerrit.plugins.reviewai.permissions;

public enum AiRole {
  USER(null, null),
  MODERATOR(USER, "message.command.moderator.required"),
  ADMINISTRATOR(MODERATOR, "message.command.debugging.administrator.required");

  private final AiRole inheritedRole;
  private final String requiredMessageKey;

  AiRole(AiRole inheritedRole, String requiredMessageKey) {
    this.inheritedRole = inheritedRole;
    this.requiredMessageKey = requiredMessageKey;
  }

  public boolean includes(AiRole requiredRole) {
    return this == requiredRole || inheritedRole != null && inheritedRole.includes(requiredRole);
  }

  public String requiredMessageKey() {
    return requiredMessageKey;
  }
}
