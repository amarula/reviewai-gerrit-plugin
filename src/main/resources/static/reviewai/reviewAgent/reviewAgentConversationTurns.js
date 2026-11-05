(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  class ReviewAgentConversationTurns {
    constructor(provider) {
      this.provider = provider;
    }

    async storeConversationTurn(change, req, conversationId, prompt, responseText, codeContext) {
      const now = Date.now();
      const normalizedResponseText = agentUtils.normalizeResponseEntrySeparators(responseText);
      let clientData =
        (req && req.client_data) || agentUtils.buildClientData(!req || req.turn_index === 0);
      if (Array.isArray(req?.external_contexts)) {
        // Native chat normally inherits unchanged context from the preceding turn. Our
        // one-shot attachment is removed on acceptance, so persist each turn's actual chips.
        const data = JSON.parse(clientData);
        clientData = JSON.stringify({
          ...data,
          overridesPreviousTurn: true,
          actionId: req.action?.id || data.actionId || agentUtils.defaultActionId,
          contextItems: req.external_contexts.map(item => item.type_id === reviewAi.codeContext.typeId
            ? {...item, reviewai_consumed: true} : item),
          isBackgroundRequest: data.isBackgroundRequest || false,
        });
      }
      const turn = {
        code_context: codeContext
          ? {revision: codeContext.revision, path: codeContext.path} : undefined,
        user_input: {
          user_question: prompt,
          client_data: clientData,
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
