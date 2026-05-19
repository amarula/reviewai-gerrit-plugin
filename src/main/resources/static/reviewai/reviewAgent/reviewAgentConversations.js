(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  reviewAi.agentConversationMethods = {
    async listChatConversations(change) {
      if (!(await this._canAiReviewChange(change))) {
        return [];
      }

      const storedConversations = await this._listStoredConversations(change);
      const entries = await this._fetchEntries(change);
      const reviewAiCommentsEntries = this._filterStoredConversationEntries(
        change,
        entries,
        storedConversations
      );
      if (!reviewAiCommentsEntries.length) {
        return storedConversations;
      }
      const lastEntry = reviewAiCommentsEntries[reviewAiCommentsEntries.length - 1];
      return this._upsertReviewAiCommentsConversation(
        change,
        storedConversations,
        agentUtils.parseTimestampMillis(lastEntry.updated)
      );
    },

    async getChatConversation(change, conversationId) {
      if (!conversationId || !(await this._canAiReviewChange(change))) {
        return [];
      }

      const storedConversation = await this._getStoredConversation(change, conversationId);
      if (storedConversation) {
        return this._getHistoryBackedStoredTurns(change, storedConversation);
      }

      if (
        !agentUtils.isSameConversationId(
          conversationId,
          this.conversationTurns.conversationId(change)
        )
      ) {
        return [];
      }

      const storedConversations = await this._listStoredConversations(change);
      const entries = await this._fetchEntries(change);
      return this.conversationTurns.entriesToConversationTurns(
        this._filterStoredConversationEntries(change, entries, storedConversations)
      );
    },

    async _getHistoryBackedStoredTurns(change, storedConversation) {
      const storedTurns = Array.isArray(storedConversation && storedConversation.turns)
        ? storedConversation.turns
        : [];
      const storedConversationId = storedConversation && storedConversation.id;
      const includeNewTurns = agentUtils.isSameConversationId(
        storedConversationId,
        this.conversationTurns.conversationId(change)
      );

      try {
        const storedConversations = await this._listStoredConversations(change);
        const entries = await this._fetchEntries(change);
        const historyTurns = this.conversationTurns.entriesToConversationTurns(
          this._filterStoredConversationEntries(
            change,
            entries,
            storedConversations,
            storedConversationId
          )
        );
        return this.conversationTurns.mergeStoredTurnsWithHistory(
          storedTurns,
          historyTurns,
          includeNewTurns
        );
      } catch {
        return storedTurns;
      }
    },

    _upsertReviewAiCommentsConversation(change, storedConversations, timestampMillis) {
      const conversationId = this.conversationTurns.conversationId(change);
      const conversationIndex = storedConversations.findIndex(conversation =>
        agentUtils.isSameConversationId(conversation && conversation.id, conversationId)
      );
      const historyConversation = {
        id: conversationId,
        title: 'ReviewAI comments',
        timestamp_millis: timestampMillis,
      };
      if (conversationIndex === -1) {
        return storedConversations.concat([historyConversation]);
      }

      const conversations = storedConversations.slice();
      const storedConversation = conversations[conversationIndex] || {};
      const storedTimestamp =
        Number(storedConversation.timestamp_millis || storedConversation.timestampMillis) || 0;
      conversations[conversationIndex] = {
        ...storedConversation,
        title: storedConversation.title || historyConversation.title,
        timestamp_millis: Math.max(storedTimestamp, timestampMillis),
      };
      return conversations;
    },
  };
})(window);
