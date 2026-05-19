(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  reviewAi.agentConversationTurnMethods = {
    _turnUserQuestion(turn) {
      const userInput = turn && turn.user_input;
      return (userInput && userInput.user_question) || '';
    },

    _mergeStoredTurnsWithHistory(storedTurns, historyTurns, includeNewTurns) {
      if (!historyTurns.length) {
        return storedTurns;
      }

      const mergedTurns = storedTurns.slice();
      const matchedStoredTurnIndexes = new Set();
      historyTurns.forEach(historyTurn => {
        const storedTurnIndex = this._findStoredTurnIndexForHistory(
          mergedTurns,
          historyTurn,
          matchedStoredTurnIndexes
        );
        if (storedTurnIndex !== -1) {
          mergedTurns[storedTurnIndex] = this._mergeTurnWithHistory(
            mergedTurns[storedTurnIndex],
            historyTurn
          );
          matchedStoredTurnIndexes.add(storedTurnIndex);
        } else if (
          includeNewTurns &&
          !this._hasEquivalentStoredAssistantResponse(mergedTurns, historyTurn)
        ) {
          mergedTurns.push(historyTurn);
        }
      });
      return includeNewTurns ? this._sortTurnsByTimestamp(mergedTurns) : mergedTurns;
    },

    _findStoredTurnIndexForHistory(storedTurns, historyTurn, matchedStoredTurnIndexes) {
      const historyQuestion = this._turnUserQuestion(historyTurn);
      if (!historyQuestion || this._isAutomaticReviewQuestion(historyQuestion)) {
        return -1;
      }

      return storedTurns.findIndex((storedTurn, index) => {
        if (matchedStoredTurnIndexes.has(index)) {
          return false;
        }
        if (this._turnUserQuestion(storedTurn) !== historyQuestion) {
          return false;
        }
        return this._isSamePatchSetTurn(storedTurn, historyTurn);
      });
    },

    _isSamePatchSetTurn(leftTurn, rightTurn) {
      const leftPatchSet = leftTurn && (leftTurn.patch_set || leftTurn.patchSet);
      const rightPatchSet = rightTurn && (rightTurn.patch_set || rightTurn.patchSet);
      return !leftPatchSet || !rightPatchSet || leftPatchSet === rightPatchSet;
    },

    _mergeTurnWithHistory(storedTurn, historyTurn) {
      const refreshedResponse = historyTurn.response || historyTurn.chat_response;
      return {
        ...storedTurn,
        response: refreshedResponse || storedTurn.response,
        chat_response: refreshedResponse || storedTurn.chat_response,
        timestamp_millis: historyTurn.timestamp_millis || storedTurn.timestamp_millis,
      };
    },

    _sortTurnsByTimestamp(turns) {
      return turns
        .map((turn, index) => ({turn, index}))
        .sort((left, right) => {
          const leftTimestamp = Number(left.turn && left.turn.timestamp_millis) || 0;
          const rightTimestamp = Number(right.turn && right.turn.timestamp_millis) || 0;
          return leftTimestamp - rightTimestamp || left.index - right.index;
        })
        .map(item => item.turn);
    },

    _automaticReviewPrompt(entry) {
      const patchSet = entry && entry.patchSet;
      return patchSet ? `Automatic review for Patch Set ${patchSet}` : 'Automatic review';
    },

    _isAutomaticReviewQuestion(question) {
      return /^Automatic review(?:\b| for Patch Set \d+$)/.test(question || '');
    },

    _hasEquivalentStoredAssistantResponse(storedTurns, historyTurn) {
      if (!this._isAutomaticReviewQuestion(this._turnUserQuestion(historyTurn))) {
        return false;
      }

      const historyResponseText = this._normalizeTurnResponseText(historyTurn);
      if (!historyResponseText) {
        return false;
      }
      return storedTurns.some(
        storedTurn =>
          this._isSamePatchSetTurn(storedTurn, historyTurn) &&
          this._normalizeTurnResponseText(storedTurn) === historyResponseText
      );
    },

    _normalizeTurnResponseText(turn) {
      const response = turn && (turn.response || turn.chat_response);
      const responseParts = response && response.response_parts;
      if (!Array.isArray(responseParts)) {
        return '';
      }
      return responseParts
        .map(part => (part && part.text) || '')
        .join('\n\n')
        .replace(/\s+/g, ' ')
        .trim();
    },

    _entryTimestampMillis(entry) {
      return agentUtils.parseTimestampMillis(entry && entry.updated);
    },

    _isNextAssistantEntryForCurrentTurn(currentTurn, entry) {
      if (!currentTurn || !currentTurn.response) {
        return false;
      }
      const currentPatchSet = currentTurn.patch_set || currentTurn.patchSet;
      if (currentPatchSet && entry.patchSet && currentPatchSet !== entry.patchSet) {
        return false;
      }
      const responseTimestamp =
        Number(currentTurn.response.timestamp_millis || currentTurn.timestamp_millis) || 0;
      const entryTimestamp = this._entryTimestampMillis(entry);
      return Math.abs(entryTimestamp - responseTimestamp) <= 60000;
    },

    _newTurn(entry, userQuestion, hasClientDataOverride) {
      return {
        user_input: {
          user_question: userQuestion,
          client_data: agentUtils.buildClientData(!hasClientDataOverride),
        },
        regeneration_index: 0,
        timestamp_millis: this._entryTimestampMillis(entry),
        patch_set: entry.patchSet,
      };
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

    _appendEntrySeparators(turns) {
      turns.forEach(turn => {
        const response = turn && turn.response;
        const responseParts = response && response.response_parts;
        if (!Array.isArray(responseParts) || responseParts.length < 2) {
          return;
        }

        responseParts.slice(0, -1).forEach(part => {
          if (!part || !part.text) {
            return;
          }
          part.text = `${part.text}\n\n---\n\n`;
        });
      });
    },

    _entriesToConversationTurns(entries) {
      const turns = [];
      let currentTurn = null;
      let hasClientDataOverride = false;

      agentUtils.orderAssistantEntriesWithinTurns(entries).forEach(entry => {
        if (entry.role === 'user' && !entry.systemMessage) {
          currentTurn = this._newTurn(entry, entry.message || '', hasClientDataOverride);
          turns.push(currentTurn);
          hasClientDataOverride = true;
          return;
        }

        if (!agentUtils.isAssistantEntry(entry)) {
          return;
        }

        if (
          !currentTurn ||
          !this._turnUserQuestion(currentTurn) ||
          (this._turnUserQuestion(currentTurn) &&
            currentTurn.response &&
            !this._isNextAssistantEntryForCurrentTurn(currentTurn, entry))
        ) {
          currentTurn = this._newTurn(
            entry,
            this._automaticReviewPrompt(entry),
            hasClientDataOverride
          );
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

      this._appendEntrySeparators(turns);
      return turns;
    },

    async _storeConversationTurn(change, req, conversationId, prompt, responseText) {
      if (!prompt || !String(prompt).trim()) {
        return;
      }
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
