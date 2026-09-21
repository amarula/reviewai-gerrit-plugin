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

package com.googlesource.gerrit.plugins.reviewai;

import com.google.gerrit.extensions.registration.DynamicSet;
import com.google.gerrit.extensions.webui.WebUiPlugin;
import com.google.inject.servlet.ServletModule;
import com.googlesource.gerrit.plugins.reviewai.avatar.ReviewAiAvatarProvider;
import com.googlesource.gerrit.plugins.reviewai.avatar.ReviewAiAvatarServlet;

public class HttpModule extends ServletModule {
  @Override
  protected void configureServlets() {
    DynamicSet.bind(binder(), WebUiPlugin.class).toInstance(WebUiPlugin.js("init.js"));
    serve("/" + ReviewAiAvatarProvider.AVATAR_ENDPOINT_PATH).with(ReviewAiAvatarServlet.class);
  }
}
