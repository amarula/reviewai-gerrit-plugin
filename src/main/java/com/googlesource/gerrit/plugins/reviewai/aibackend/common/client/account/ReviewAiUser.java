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

import com.google.gerrit.entities.Account;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.data.AccountAttribute;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit.GerritComment;
import java.util.Objects;

/** Shared account-id based checks for identifying the configured ReviewAI user. */
public final class ReviewAiUser {
  private ReviewAiUser() {}

  public static boolean matches(IdentifiedUser user, Account.Id aiAccountId) {
    return user != null && matches(user.getAccountId(), aiAccountId);
  }

  public static boolean matches(Account.Id accountId, Account.Id aiAccountId) {
    return aiAccountId != null && aiAccountId.equals(accountId);
  }

  public static boolean matches(GerritComment comment, int aiAccountId) {
    return comment != null && matches(comment.getAuthor(), aiAccountId);
  }

  public static boolean matches(GerritComment.Author author, int aiAccountId) {
    return author != null && matches(author.getAccountId(), aiAccountId);
  }

  public static boolean matches(int accountId, int aiAccountId) {
    return accountId == aiAccountId;
  }

  public static boolean matches(Integer accountId, int aiAccountId) {
    return accountId != null && matches(accountId.intValue(), aiAccountId);
  }

  public static boolean matches(
      AccountAttribute author, Account.Id aiAccountId, String aiUserName, String aiUserEmail) {
    if (author == null) {
      return false;
    }
    if (aiAccountId != null && matches(author.accountId, aiAccountId.get())) {
      return true;
    }
    return Objects.equals(aiUserName, author.username)
        || Objects.equals(aiUserEmail, author.email);
  }
}
