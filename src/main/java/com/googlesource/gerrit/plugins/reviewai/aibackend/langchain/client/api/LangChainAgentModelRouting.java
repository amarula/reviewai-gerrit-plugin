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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.config.AiAgentRole;
import com.googlesource.gerrit.plugins.reviewai.config.AiModelRoute;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import java.util.EnumMap;
import java.util.Map;

public final class LangChainAgentModelRouting {
  private final Configuration config;
  private final Map<AiAgentRole, AiModelRoute> agentModels;

  LangChainAgentModelRouting(Configuration config) {
    this.config = config;
    Map<AiAgentRole, AiModelRoute> routes = new EnumMap<>(AiAgentRole.class);
    if (config != null) {
      config
          .getAiAgentModels()
          .forEach(
              (role, route) ->
                  AiModelRoute.parse(route).ifPresent(modelRoute -> routes.put(role, modelRoute)));
    }
    agentModels = Map.copyOf(routes);
  }

  <T> T execute(
      ChangeSetData data,
      boolean useConversationHistory,
      AiModelRoute explicitRoute,
      RoutedRequest<T> request)
      throws Exception {
    AiModelRoute route = explicitRoute;
    if (route == null
        && !agentModels.isEmpty()
        && !Boolean.TRUE.equals(data.getSuggestMode())
        && !useConversationHistory) {
      AiAgentRole role = roleForRequest(data);
      route = role == null ? null : agentModels.get(role);
    }
    return execute(route, request);
  }

  private <T> T execute(AiModelRoute route, RoutedRequest<T> request) throws Exception {
    return route == null
        ? request.call(false)
        : config.withAiModelRoute(route, () -> request.call(true));
  }

  static AiAgentRole roleForRequest(ChangeSetData data) {
    if (data.getReviewAssistantStage() == null) {
      return AiAgentRole.CODE;
    }
    return switch (data.getReviewAssistantStage()) {
      case REVIEW_CODE, REVIEW_REITERATED -> AiAgentRole.CODE;
      case REVIEW_COMMIT_MESSAGE -> AiAgentRole.COMMIT_MESSAGE;
      case REVIEW_SPECIALIZED_AGENT -> AiAgentRole.valueOf(data.getSpecializedAgentName());
      case REVIEW_CONCERNS, FIND_NEW_ISSUES -> reviewerRole(data);
      default -> null;
    };
  }

  private static AiAgentRole reviewerRole(ChangeSetData data) {
    if (data.getConcernWorkflowInput() != null
        && data.getConcernWorkflowInput().getConcerns() != null) {
      ConcernReviewerId reviewer = data.getConcernWorkflowInput().getConcerns().getReviewer();
      if (reviewer != null) {
        if (reviewer.getKind() == ConcernReviewerId.Kind.SINGLE_AGENT
            || "PATCHSET".equals(reviewer.getName())) {
          return AiAgentRole.CODE;
        }
        return AiAgentRole.valueOf(reviewer.getName());
      }
    }
    return data.getSpecializedAgentName() == null
        ? AiAgentRole.CODE
        : AiAgentRole.valueOf(data.getSpecializedAgentName());
  }

  @FunctionalInterface
  interface RoutedRequest<T> {
    T call(boolean routeOverridden) throws Exception;
  }
}
