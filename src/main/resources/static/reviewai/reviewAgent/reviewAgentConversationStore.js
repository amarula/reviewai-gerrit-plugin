(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

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

    _hasReviewAiCommentsConversation(change, conversations) {
      const conversationId = this._conversationId(change);
      return conversations.some(conversation =>
        agentUtils.isSameConversationId(conversation && conversation.id, conversationId)
      );
    },

    _filterStoredConversationEntries(change, entries, conversations, ignoredConversationId) {
      const conversationId = this._conversationId(change);
      const userMessages = new Map();
      const assistantMessages = new Map();

      conversations.forEach(conversation => {
        if (
          !conversation ||
          agentUtils.isSameConversationId(conversation.id, conversationId) ||
          agentUtils.isSameConversationId(conversation.id, ignoredConversationId)
        ) {
          return;
        }
        const turns = Array.isArray(conversation.turns) ? conversation.turns : [];
        turns.forEach(turn => {
          this._incrementMessageCount(
            userMessages,
            turn && turn.user_input && turn.user_input.user_question
          );
          this._incrementMessageCount(assistantMessages, this._turnResponseText(turn));
        });
      });

      return entries.filter(entry => {
        if (entry.role === 'user' && !entry.systemMessage) {
          return !this._consumeMessageCount(userMessages, entry.message || '');
        }
        if (agentUtils.isAssistantEntry(entry)) {
          return !this._consumeMessageCount(
            assistantMessages,
            agentUtils.formatAgentEntry(entry)
          );
        }
        return true;
      });
    },

    _turnResponseText(turn) {
      const response = turn && (turn.response || turn.chat_response);
      const responseParts = response && response.response_parts;
      if (!Array.isArray(responseParts)) {
        return '';
      }
      return responseParts.map(part => (part && part.text) || '').join('\n\n');
    },

    _incrementMessageCount(messages, message) {
      if (!message) {
        return;
      }
      messages.set(message, (messages.get(message) || 0) + 1);
    },

    _consumeMessageCount(messages, message) {
      const count = messages.get(message) || 0;
      if (!count) {
        return false;
      }
      if (count === 1) {
        messages.delete(message);
      } else {
        messages.set(message, count - 1);
      }
      return true;
    },
  };
})(window);
