package com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.prompt.agents.level2;

import com.googlesource.gerrit.plugins.reviewai.aibackend.common.client.api.gerrit.GerritChange;
import com.googlesource.gerrit.plugins.reviewai.aibackend.common.model.data.ChangeSetData;
import com.googlesource.gerrit.plugins.reviewai.config.Configuration;
import com.googlesource.gerrit.plugins.reviewai.interfaces.aibackend.common.client.code.context.ICodeContextPolicy;

public class AiPromptSpecializedHistoricalRepetition extends AiPromptSpecializedReviewCollector {

  public AiPromptSpecializedHistoricalRepetition(
      Configuration config,
      ChangeSetData changeSetData,
      GerritChange change,
      ICodeContextPolicy codeContextPolicy) {
    super(
        config,
        changeSetData,
        change,
        codeContextPolicy,
        "agents/level2/stages/historical-repetition/prompts");
  }

  @Override
  protected String getCollectorRole() {
    return prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_HISTORICAL_REPETITION");
  }

  @Override
  protected String getCollectorRules() {
    return prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_HISTORICAL_REPETITION_RULES");
  }

  @Override
  protected String getCollectorResponseFormat() {
    return prompt("DEFAULT_AI_ASSISTANT_INSTRUCTIONS_SPECIALIZED_HISTORICAL_REPETITION_RESPONSE_FORMAT");
  }

  @Override
  protected String getCollectorMessage() {
    return prompt("DEFAULT_AI_MESSAGE_SPECIALIZED_HISTORICAL_REPETITION");
  }
}
