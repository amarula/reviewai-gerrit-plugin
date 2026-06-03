(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  class ReviewAgentConversationTurns {
    constructor(provider) {
      this.provider = provider;
    }

    async storeConversationTurn(change, req, conversationId, prompt, responseText) {
      const now = Date.now();
      const normalizedResponseText = agentUtils.normalizeResponseEntrySeparators(responseText);
      const turn = {
        user_input: {
          user_question: prompt,
          client_data:
            (req && req.client_data) || agentUtils.buildClientData(!req || req.turn_index === 0),
        },
        response: agentUtils.buildChatResponse(normalizedResponseText),
        regeneration_index: (req && req.regeneration_index) || 0,
        timestamp_millis: now,
      };
      await this.provider._appendStoredConversationTurn(change, {
        conversationId,
        conversation_id: conversationId,
        title: agentUtils.getConversationTitle(prompt),
        timestampMillis: now,
        timestamp_millis: now,
        turnIndex: req && Number.isInteger(req.turn_index) ? req.turn_index : undefined,
        turn_index: req && Number.isInteger(req.turn_index) ? req.turn_index : undefined,
        turn,
      });
    }

    conversationId(change) {
      return agentUtils.stableUuid(`reviewai-${agentUtils.getChangeNumber(change) || 'change'}`);
    }
  }

  reviewAi.ReviewAgentConversationTurns = ReviewAgentConversationTurns;
})(window);
