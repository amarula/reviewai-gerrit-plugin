(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  reviewAi.agentConversationMethods = {
    async listChatConversations(change) {
      if (!(await this._canAiReviewChange(change))) {
        return [];
      }

      const storedConversations = await this._listStoredConversations(change);
      if (this._hasReviewAiCommentsConversation(change, storedConversations)) {
        return storedConversations;
      }

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
      return storedConversations.concat([
        {
          id: this._conversationId(change),
          title: 'ReviewAI comments',
          timestamp_millis: agentUtils.parseTimestampMillis(lastEntry.updated),
        },
      ]);
    },

    async getChatConversation(change, conversationId) {
      if (!conversationId || !(await this._canAiReviewChange(change))) {
        return [];
      }

      const storedConversation = await this._getStoredConversation(change, conversationId);
      if (storedConversation) {
        return this._getHistoryBackedStoredTurns(change, storedConversation);
      }

      if (!agentUtils.isSameConversationId(conversationId, this._conversationId(change))) {
        return [];
      }

      const storedConversations = await this._listStoredConversations(change);
      const entries = await this._fetchEntries(change);
      return this._entriesToConversationTurns(
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
        this._conversationId(change)
      );

      try {
        const storedConversations = await this._listStoredConversations(change);
        const entries = await this._fetchEntries(change);
        const historyTurns = this._entriesToConversationTurns(
          this._filterStoredConversationEntries(
            change,
            entries,
            storedConversations,
            storedConversationId
          )
        );
        return this._mergeStoredTurnsWithHistory(storedTurns, historyTurns, includeNewTurns);
      } catch {
        return storedTurns;
      }
    },
  };
})(window);
