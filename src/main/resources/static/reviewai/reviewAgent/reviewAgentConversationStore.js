(function(global) {
  const reviewAi = global.ReviewAi;

  reviewAi.agentConversationStoreMethods = {
    _conversationStore(change, input) {
      return this.plugin
        .restApi()
        .post(`/changes/${change._number}/${this.pluginName}~ai-review-agent-conversations`, input);
    },

    async _listStoredConversations(change) {
      const output = await this._conversationStore(change, {action: 'list'});
      return Array.isArray(output && output.conversations) ? output.conversations : [];
    },

    async _getStoredConversation(change, conversationId) {
      if (!conversationId) {
        return null;
      }
      const output = await this._conversationStore(change, {
        action: 'get',
        conversationId,
        conversation_id: conversationId,
      });
      return output && output.conversation;
    },

    async _appendStoredConversationTurn(change, input) {
      return this._conversationStore(change, {
        action: 'append',
        ...input,
      });
    },
  };
})(window);
