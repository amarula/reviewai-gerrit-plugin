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

import com.google.gerrit.common.Nullable;
import com.google.gerrit.entities.Account.Id;
import com.google.gerrit.extensions.annotations.PluginCanonicalWebUrl;
import com.google.gerrit.extensions.annotations.PluginName;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.avatar.AvatarProvider;
import com.google.gerrit.server.config.CanonicalWebUrl;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.account.ReviewAiUser;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.utils.HashUtils;
import java.util.Locale;

/**
 * Provides the ReviewAI avatar for the configured Gerrit user and Gravatar URLs for other users.
 */
@Singleton
public class ReviewAiAvatarProvider implements AvatarProvider {
  public static final String AVATAR_ENDPOINT_PATH = "reviewai-avatar.svg";
  private static final String DEFAULT_GRAVATAR_HASH = "00000000000000000000000000000000";

  private final Id aiAccountId;
  private final String avatarUrl;
  private final String gravatarBaseUrl;
  private final String avatarType;
  private final String avatarRating;
  private final String changeAvatarUrl;
  private final boolean defaultImage;

  @Inject
  ReviewAiAvatarProvider(
      PluginConfigFactory cfgFactory,
      AccountCache accountCache,
      @PluginName String pluginName,
      @PluginCanonicalWebUrl String pluginCanonicalWebUrl,
      @CanonicalWebUrl @Nullable String canonicalWebUrl) {
    PluginConfig cfg = cfgFactory.getFromGerritConfig(pluginName);
    String gerritUserName = cfg.getString(Configuration.KEY_GERRIT_USERNAME);
    aiAccountId =
        gerritUserName == null
            ? null
            : accountCache
                .getByUsername(gerritUserName)
                .map(state -> state.account().id())
                .orElse(null);
    avatarUrl = avatarEndpointUrl(pluginCanonicalWebUrl);
    PluginConfig gravatarCfg =
        cfgFactory.getFromGerritConfig(ReviewAiAvatarPluginDetector.AVATARS_GRAVATAR_PLUGIN_NAME);
    avatarType = gravatarCfg.getString("type", "identicon");
    avatarRating = gravatarCfg.getString("rating", "pg");
    gravatarBaseUrl =
        gravatarBaseUrl(
            canonicalWebUrl, gravatarCfg.getString("gravatarUrl", "www.gravatar.com/avatar/"));
    changeAvatarUrl = gravatarCfg.getString("changeAvatarUrl", "http://www.gravatar.com");
    defaultImage = gravatarCfg.getBoolean("defaultImage", true);
  }

  @Override
  public String getUrl(IdentifiedUser forUser, int imageSize) {
    if (ReviewAiUser.matches(forUser, aiAccountId)) {
      return avatarUrl;
    }
    return getGravatarUrl(forUser, imageSize);
  }

  @Override
  public String getChangeAvatarUrl(IdentifiedUser forUser) {
    return changeAvatarUrl;
  }

  private String avatarEndpointUrl(String pluginCanonicalWebUrl) {
    return pluginCanonicalWebUrl.endsWith("/")
        ? pluginCanonicalWebUrl + AVATAR_ENDPOINT_PATH
        : pluginCanonicalWebUrl + "/" + AVATAR_ENDPOINT_PATH;
  }

  private String gravatarBaseUrl(String canonicalWebUrl, String gravatarUrl) {
    if (gravatarUrl.matches("^https?://.+")) {
      return gravatarUrl;
    }
    boolean ssl = canonicalWebUrl != null && canonicalWebUrl.startsWith("https://");
    return (ssl ? "https://" : "http://") + gravatarUrl;
  }

  private String getGravatarUrl(IdentifiedUser forUser, int imageSize) {
    String email = forUser.getAccount().preferredEmail();
    if ((email == null || email.isBlank()) && !defaultImage) {
      return null;
    }

    StringBuilder url = new StringBuilder(gravatarBaseUrl);
    url.append(
        email == null || email.isBlank()
            ? DEFAULT_GRAVATAR_HASH
            : HashUtils.md5Hex(email.trim().toLowerCase(Locale.ROOT)));
    url.append(".jpg");
    url.append("?d=").append(avatarType).append("&r=").append(avatarRating);
    if (imageSize > 0) {
      url.append("&s=").append(imageSize);
    }
    return url.toString();
  }
}
