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

package com.googlesource.gerrit.plugins.reviewai.aibackend.langchain.client.api.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2.SpecializedReviewAgentDefinition;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ConcernReviewerId;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewConcernLedger;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.review.ReviewerConcerns;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

final class SpecializedReviewConcernPlanSelector {
  private SpecializedReviewConcernPlanSelector() {}

  static List<SpecializedReviewTriage.AgentPlan> select(
      List<SpecializedReviewTriage.AgentPlan> enabledPlans,
      SpecializedReviewTriage triage,
      ReviewConcernLedger previousLedger,
      Predicate<String> agentInScope) {
    List<SpecializedReviewTriage.AgentPlan> plans = new ArrayList<>(enabledPlans);
    if (previousLedger == null) {
      return plans;
    }

    previousLedger.normalize();
    Set<String> selectedAgents =
        plans.stream()
            .map(SpecializedReviewTriage.AgentPlan::getAgent)
            .map(SpecializedReviewConcernPlanSelector::normalizedAgentName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    for (ReviewerConcerns concerns : previousLedger.getReviewers()) {
      concerns.normalize();
      ConcernReviewerId reviewer = concerns.getReviewer();
      if (reviewer == null
          || reviewer.getKind() != ConcernReviewerId.Kind.SPECIALIZED_AGENT
          || reviewer.getName() == null
          || concerns.getConcerns().isEmpty()
          || !agentInScope.test(reviewer.getName())) {
        continue;
      }
      String agent = normalizedAgentName(reviewer.getName());
      if (selectedAgents.add(agent)) {
        plans.add(planForStoredConcerns(triage, agent));
      }
    }
    return plans;
  }

  private static SpecializedReviewTriage.AgentPlan planForStoredConcerns(
      SpecializedReviewTriage triage, String agent) {
    if (triage != null && triage.getAgents() != null) {
      Optional<SpecializedReviewTriage.AgentPlan> triagePlan =
          triage.getAgents().stream()
              .filter(plan -> plan.getAgent() != null)
              .filter(plan -> agent.equals(normalizedAgentName(plan.getAgent())))
              .findFirst();
      if (triagePlan.isPresent()) {
        return triagePlan.get();
      }
    }
    SpecializedReviewTriage.AgentPlan plan = new SpecializedReviewTriage.AgentPlan();
    plan.setAgent(agent);
    plan.setEnabled(true);
    return plan;
  }

  private static String normalizedAgentName(String agent) {
    return SpecializedReviewAgentDefinition.normalizeName(agent);
  }
}
