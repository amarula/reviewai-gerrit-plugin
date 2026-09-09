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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.api.gerrit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class GerritCommentTest {
  @Test
  public void returnsPositiveAuthorAccountId() {
    GerritComment comment = commentWithAuthorAccountId(42);

    assertEquals(Integer.valueOf(42), comment.getAuthorAccountId());
  }

  @Test
  public void returnsNullWithoutAuthor() {
    assertNull(new GerritComment().getAuthorAccountId());
  }

  @Test
  public void returnsNullForInvalidAuthorAccountId() {
    assertNull(commentWithAuthorAccountId(0).getAuthorAccountId());
  }

  private GerritComment commentWithAuthorAccountId(int accountId) {
    GerritComment.Author author = new GerritComment.Author();
    author.setAccountId(accountId);
    GerritComment comment = new GerritComment();
    comment.setAuthor(author);
    return comment;
  }
}
