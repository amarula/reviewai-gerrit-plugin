(function(global) {
  const reviewAi = global.ReviewAi;

  reviewAi.agentConversationMethods = {
    async listChatConversations(change) {
      if (!(await this._canAiReviewChange(change))) {
        return [];
      }

      return this._listStoredConversations(change);
    },

    async getChatConversation(change, conversationId) {
      if (!conversationId || !(await this._canAiReviewChange(change))) {
        return [];
      }

      const storedConversation = await this._getStoredConversation(change, conversationId);
      if (storedConversation) {
        reviewAi.agentUtils.linkConversationReplyHeaders(
          storedConversation,
          await this._fetchEntries(change)
        );
        return Array.isArray(storedConversation.turns) ? storedConversation.turns : [];
      }

      return [];
    },
  };
})(window);
