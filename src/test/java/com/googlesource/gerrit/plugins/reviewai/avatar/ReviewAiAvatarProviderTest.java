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

package com.googlesource.gerrit.plugins.reviewai.avatar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.gerrit.entities.Account;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.account.AccountCache;
import com.google.gerrit.server.account.AccountState;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.config.PluginConfigFactory;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.Optional;
import org.junit.Test;

public class ReviewAiAvatarProviderTest {
  private static final String PLUGIN_NAME = "reviewai-gerrit-plugin";
  private static final String PLUGIN_URL = "/plugins/" + PLUGIN_NAME;
  private static final String CANONICAL_WEB_URL = "https://gerrit.example.com/";
  private static final String REVIEW_AI_USER_NAME = "gpt";
  private static final String NORMAL_USER_EMAIL = "pat@example.com";
  private static final Account.Id AI_ACCOUNT_ID = Account.id(7);
  private static final Account.Id OTHER_ACCOUNT_ID = Account.id(1001);

  @Test
  public void returnsAvatarForConfiguredAiAccount() {
    ReviewAiAvatarProvider provider = newProvider(REVIEW_AI_USER_NAME);

    assertEquals(
        PLUGIN_URL + "/reviewai-avatar.svg", provider.getUrl(user(AI_ACCOUNT_ID, "AI"), 32));
  }

  @Test
  public void returnsGravatarWhenAiAccountIdIsUnavailable() {
    ReviewAiAvatarProvider provider = newProviderWithoutAccountId(REVIEW_AI_USER_NAME);

    assertEquals(
        "https://www.gravatar.com/avatar/00000000000000000000000000000000.jpg"
            + "?d=identicon&r=pg&s=32",
        provider.getUrl(user(AI_ACCOUNT_ID, REVIEW_AI_USER_NAME), 32));
  }

  @Test
  public void returnsGravatarForOtherUsers() {
    ReviewAiAvatarProvider provider = newProvider(REVIEW_AI_USER_NAME);

    assertEquals(
        "https://www.gravatar.com/avatar/3ddcaf169f35457c40bc5960e69c4601.jpg"
            + "?d=identicon&r=pg&s=32",
        provider.getUrl(user(OTHER_ACCOUNT_ID, "Pat", NORMAL_USER_EMAIL), 32));
  }

  @Test
  public void returnsDefaultGravatarForOtherUsersWithoutEmail() {
    ReviewAiAvatarProvider provider = newProvider(REVIEW_AI_USER_NAME);

    assertEquals(
        "https://www.gravatar.com/avatar/00000000000000000000000000000000.jpg"
            + "?d=identicon&r=pg&s=32",
        provider.getUrl(user(OTHER_ACCOUNT_ID, "Pat"), 32));
  }

  @Test
  public void returnsNoAvatarForOtherUsersWithoutEmailWhenDefaultImageIsDisabled() {
    ReviewAiAvatarProvider provider = newProvider(REVIEW_AI_USER_NAME, false);

    assertNull(provider.getUrl(user(OTHER_ACCOUNT_ID, "Pat"), 32));
  }

  private ReviewAiAvatarProvider newProvider(String gerritUserName) {
    return newProvider(gerritUserName, true);
  }

  private ReviewAiAvatarProvider newProvider(String gerritUserName, boolean defaultImage) {
    PluginConfigFactory cfgFactory = mock(PluginConfigFactory.class);
    AccountCache accountCache = mock(AccountCache.class);
    PluginConfig cfg = mock(PluginConfig.class);
    PluginConfig gravatarCfg = gravatarConfig(defaultImage);
    AccountState aiAccount = account(AI_ACCOUNT_ID);
    when(cfgFactory.getFromGerritConfig(PLUGIN_NAME)).thenReturn(cfg);
    when(cfgFactory.getFromGerritConfig("avatars-gravatar")).thenReturn(gravatarCfg);
    when(cfg.getString(Configuration.KEY_GERRIT_USERNAME)).thenReturn(gerritUserName);
    when(accountCache.getByUsername(gerritUserName)).thenReturn(Optional.of(aiAccount));
    return new ReviewAiAvatarProvider(
        cfgFactory, accountCache, PLUGIN_NAME, PLUGIN_URL, CANONICAL_WEB_URL);
  }

  private ReviewAiAvatarProvider newProviderWithoutAccountId(String gerritUserName) {
    PluginConfigFactory cfgFactory = mock(PluginConfigFactory.class);
    AccountCache accountCache = mock(AccountCache.class);
    PluginConfig cfg = mock(PluginConfig.class);
    PluginConfig gravatarCfg = gravatarConfig(true);
    when(cfgFactory.getFromGerritConfig(PLUGIN_NAME)).thenReturn(cfg);
    when(cfgFactory.getFromGerritConfig("avatars-gravatar")).thenReturn(gravatarCfg);
    when(cfg.getString(Configuration.KEY_GERRIT_USERNAME)).thenReturn(gerritUserName);
    when(accountCache.getByUsername(gerritUserName)).thenReturn(Optional.empty());
    return new ReviewAiAvatarProvider(
        cfgFactory, accountCache, PLUGIN_NAME, PLUGIN_URL, CANONICAL_WEB_URL);
  }

  private IdentifiedUser user(Account.Id accountId, String userName) {
    return user(accountId, userName, null);
  }

  private IdentifiedUser user(Account.Id accountId, String userName, String email) {
    IdentifiedUser user = mock(IdentifiedUser.class);
    Account account = mock(Account.class);
    when(user.getAccountId()).thenReturn(accountId);
    when(user.getUserName()).thenReturn(Optional.of(userName));
    when(user.getAccount()).thenReturn(account);
    when(account.fullName()).thenReturn(userName);
    when(account.displayName()).thenReturn(userName);
    when(account.preferredEmail()).thenReturn(email);
    when(account.getName()).thenReturn(userName);
    return user;
  }

  private AccountState account(Account.Id accountId) {
    AccountState accountState = mock(AccountState.class);
    Account account = mock(Account.class);
    when(account.id()).thenReturn(accountId);
    when(accountState.account()).thenReturn(account);
    return accountState;
  }

  private PluginConfig gravatarConfig(boolean defaultImage) {
    PluginConfig cfg = mock(PluginConfig.class);
    when(cfg.getString("type", "identicon")).thenReturn("identicon");
    when(cfg.getString("rating", "pg")).thenReturn("pg");
    when(cfg.getString("gravatarUrl", "www.gravatar.com/avatar/"))
        .thenReturn("www.gravatar.com/avatar/");
    when(cfg.getString("changeAvatarUrl", "http://www.gravatar.com"))
        .thenReturn("http://www.gravatar.com");
    when(cfg.getBoolean("defaultImage", true)).thenReturn(defaultImage);
    return cfg;
  }
}
