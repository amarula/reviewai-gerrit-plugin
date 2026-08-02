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

package com.googlesource.gerrit.plugins.reviewai.avatar;

import com.google.common.io.ByteStreams;
import com.google.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Singleton
public class ReviewAiAvatarServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final String AVATAR_RESOURCE_PATH = "/static/reviewai/reviewai-avatar.svg";

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    try (InputStream avatar = getClass().getResourceAsStream(AVATAR_RESOURCE_PATH)) {
      if (avatar == null) {
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
        return;
      }
      response.setContentType("image/svg+xml");
      response.setCharacterEncoding("UTF-8");
      response.setHeader("Cache-Control", "public, max-age=86400");
      ByteStreams.copy(avatar, response.getOutputStream());
    }
  }
}
