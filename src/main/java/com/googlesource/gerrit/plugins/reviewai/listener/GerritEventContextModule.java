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

package com.googlesource.gerrit.plugins.reviewai.listener;

import com.google.gerrit.extensions.config.FactoryModule;
import com.google.gerrit.server.events.Event;
import com.google.inject.Singleton;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiTaskSpecificReviewClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.LangChainClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.code.context.OpenAiCodeContextPolicyOnDemand;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.data.ChangeSetDataProvider;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandler;
import com.googlesource.gerrit.plugins.reviewai.data.PluginDataHandlerProvider;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.ai.IAiClient;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.api.gerrit.IGerritClientPatchSet;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyNone;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.code.context.CodeContextPolicyOnDemand;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.openai.OpenAiReviewClient;
import com.googlesource.gerrit.plugins.reviewai.aibackend.openai.client.api.gerrit.GerritClientPatchSetOpenAi;
import com.googlesource.gerrit.plugins.reviewai.settings.Settings;
import lombok.extern.slf4j.Slf4j;

import static com.google.inject.Scopes.SINGLETON;

@Slf4j
public class GerritEventContextModule extends FactoryModule {
  private final Event event;
  private final Configuration config;

  public GerritEventContextModule(Configuration config, Event event) {
    this.event = event;
    this.config = config;
    log.debug("Initializing GerritEventContextModule for event type: {}", event.getType());
  }

  @Override
  protected void configure() {
    log.debug("Configuring bindings for GerritEventContextModule");

    bind(IAiClient.class).to(getAiClient());
    log.debug("Bound IOpenAIClient to: {}", getAiClient().getSimpleName());

    bind(IGerritClientPatchSet.class).to(getClientPatchSet());
    log.debug("Bound IGerritClientPatchSet to: {}", getClientPatchSet().getSimpleName());

    bind(ICodeContextPolicy.class).to(getCodeContextPolicy());
    log.debug("Bound ICodeContextPolicy to: {}", getCodeContextPolicy().getSimpleName());

    bind(Configuration.class).toInstance(config);
    bind(GerritChange.class).toInstance(new GerritChange(event));
    log.debug("GerritChange bound to instance created from event: {}", event.getType());

    bind(ChangeSetData.class).toProvider(ChangeSetDataProvider.class).in(SINGLETON);
    log.debug("ChangeSetData bound to singleton provider");

    bind(PluginDataHandler.class).toProvider(PluginDataHandlerProvider.class).in(Singleton.class);
    log.debug("PluginDataHandler bound to singleton provider");
  }

  private Class<? extends IAiClient> getAiClient() {
    return switch (config.getAiBackend()) {
      case OPENAI ->
          config.getAiReviewCommitMessages() && config.getTaskSpecificAssistants()
              ? OpenAiTaskSpecificReviewClient.class
              : OpenAiReviewClient.class;
      case LANGCHAIN -> LangChainClient.class;
    };
  }

  private Class<? extends IGerritClientPatchSet> getClientPatchSet() {
    return switch (config.getAiBackend()) {
      case OPENAI, LANGCHAIN -> GerritClientPatchSetOpenAi.class;
    };
  }

  private Class<? extends ICodeContextPolicy> getCodeContextPolicy() {
    return switch (config.getCodeContextPolicy()) {
      case NONE -> CodeContextPolicyNone.class;
      case ON_DEMAND ->
          config.getAiBackend() == Settings.AiBackends.OPENAI
              ? OpenAiCodeContextPolicyOnDemand.class
              : CodeContextPolicyOnDemand.class;
    };
  }
}
