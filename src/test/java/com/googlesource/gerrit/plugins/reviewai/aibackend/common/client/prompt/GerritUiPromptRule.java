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

package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.Assert.assertNotNull;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.util.Base64;
import org.junit.rules.ExternalResource;

/** Supplies fixed Gerrit UI prompts so prompt snapshots do not depend on network access. */
final class GerritUiPromptRule extends ExternalResource {
  private static final String PROMPTS_URL_PROPERTY = "reviewai.gerritUiPromptUrl";
  private final WireMockServer server = new WireMockServer(options().dynamicPort());
  private String previousUrl;

  @Override
  protected void before() throws Exception {
    String path = "__files/skills/gerritPrompts.ts";
    String response;
    try (var resource = getClass().getClassLoader().getResourceAsStream(path)) {
      assertNotNull(path, resource);
      response = Base64.getEncoder().encodeToString(resource.readAllBytes());
    }
    server.start();
    server.stubFor(get(urlEqualTo("/prompts")).willReturn(aResponse().withBody(response)));
    previousUrl = System.getProperty(PROMPTS_URL_PROPERTY);
    System.setProperty(PROMPTS_URL_PROPERTY, server.baseUrl() + "/prompts");
  }

  @Override
  protected void after() {
    try {
      if (previousUrl == null) {
        System.clearProperty(PROMPTS_URL_PROPERTY);
      } else {
        System.setProperty(PROMPTS_URL_PROPERTY, previousUrl);
      }
    } finally {
      server.stop();
    }
  }
}
