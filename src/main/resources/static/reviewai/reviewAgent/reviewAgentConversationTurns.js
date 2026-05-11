(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  reviewAi.agentConversationTurnMethods = {
    _turnUserQuestion(turn) {
      const userInput = turn && turn.user_input;
      return (userInput && userInput.user_question) || '';
    },

    _applyReviewScoreToTurn(turn, reviewScore) {
      if (!reviewScore || !turn || !turn.response) {
        return;
      }

      const scoreHeader = `**${reviewScore}**`;
      const responseParts = turn.response.response_parts;
      if (!Array.isArray(responseParts) || !responseParts.length) {
        turn.response.response_parts = [{id: 0, text: scoreHeader}];
        return;
      }

      const firstPart = responseParts[0];
      const firstText = (firstPart && firstPart.text) || '';
      if (firstText.startsWith(scoreHeader)) {
        return;
      }
      firstPart.text = firstText ? `${scoreHeader}\n\n${firstText}` : scoreHeader;
    },

    _entriesToConversationTurns(entries) {
      const turns = [];
      let currentTurn = null;
      let hasClientDataOverride = false;

      agentUtils.orderAssistantEntriesWithinTurns(entries).forEach(entry => {
        if (entry.role === 'user' && !entry.systemMessage) {
          currentTurn = {
            user_input: {
              user_question: entry.message || '',
              client_data: agentUtils.buildClientData(!hasClientDataOverride),
            },
            regeneration_index: 0,
            timestamp_millis: agentUtils.parseTimestampMillis(entry.updated),
          };
          turns.push(currentTurn);
          hasClientDataOverride = true;
          return;
        }

        if (!agentUtils.isAssistantEntry(entry)) {
          return;
        }

        if (!currentTurn) {
          currentTurn = {
            user_input: {
              user_question: '',
              client_data: agentUtils.buildClientData(!hasClientDataOverride),
            },
            regeneration_index: 0,
            timestamp_millis: agentUtils.parseTimestampMillis(entry.updated),
          };
          turns.push(currentTurn);
          hasClientDataOverride = true;
        }

        const reviewScore = reviewAi.entries.formatReviewScore(entry);
        const entryText = agentUtils.formatAgentEntry(entry, {
          includeReviewScore: false,
          suppressScoredPatchSetLocation: true,
        });

        if (!currentTurn.response) {
          currentTurn.response = agentUtils.buildChatResponse(entryText);
        } else {
          currentTurn.response.response_parts.push({
            id: currentTurn.response.response_parts.length,
            text: entryText,
          });
        }
        this._applyReviewScoreToTurn(currentTurn, reviewScore);
        currentTurn.response.timestamp_millis = agentUtils.parseTimestampMillis(entry.updated);
      });

      return turns;
    },

    async _storeConversationTurn(change, req, conversationId, prompt, responseText) {
      const now = Date.now();
      const turn = {
        user_input: {
          user_question: prompt,
          client_data:
            (req && req.client_data) || agentUtils.buildClientData(!req || req.turn_index === 0),
        },
        response: agentUtils.buildChatResponse(responseText),
        regeneration_index: (req && req.regeneration_index) || 0,
        timestamp_millis: now,
      };
      await this._appendStoredConversationTurn(change, {
        conversationId,
        conversation_id: conversationId,
        title: agentUtils.getConversationTitle(prompt),
        timestampMillis: now,
        timestamp_millis: now,
        turnIndex: req && Number.isInteger(req.turn_index) ? req.turn_index : undefined,
        turn_index: req && Number.isInteger(req.turn_index) ? req.turn_index : undefined,
        turn,
      });
    },

    _conversationId(change) {
      return agentUtils.stableUuid(`reviewai-${agentUtils.getChangeNumber(change) || 'change'}`);
    },
  };
})(window);
