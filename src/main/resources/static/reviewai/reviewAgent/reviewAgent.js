(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  class ReviewAiCodeReviewProvider {
    constructor(plugin, pluginName) {
      this.plugin = plugin;
      this.pluginName = pluginName;
      this.supports_add_context = false;
      this.supports_history = true;
      this.supports_more_menu = false;
      this.supports_this_change = true;
    }

    chat(req, listener) {
      this._chatAsync(req, listener);
    }

    async _chatAsync(req, listener) {
      try {
        const change = req.change;
        if (!agentUtils.getChangeNumber(change)) {
          throw new Error('ReviewAI needs a loaded Gerrit change to answer.');
        }

        const modelInfo = await this._fetchModelInfo(change);
        if (!agentUtils.canAiReview(modelInfo)) {
          throw new Error('ReviewAI is not allowed for this change.');
        }

        const prompt = this._normalizePrompt(req);
        if (!prompt) {
          throw new Error('Enter a message for ReviewAI.');
        }

        const baselineEntries = await this._fetchEntries(change);
        const baselineKeys = new Set(baselineEntries.map(agentUtils.entryKey));
        const conversationId = this._getRequestConversationId(req, change);

        const sendResult = await this._sendMessage(change, prompt, this._getRequestModelId(req));
        const directResponse =
          sendResult && (sendResult.response_text || sendResult.responseText);
        const responseText = agentUtils.isDirectResponsePrompt(prompt)
          ? directResponse
          : agentUtils.joinAgentResponses(
              directResponse,
              await this._waitForAssistantReply(change, baselineKeys, {
                excludeDynamicConfiguration: Boolean(directResponse),
              })
            );
        await this._storeConversationTurn(change, req, conversationId, prompt, responseText);
        listener.emitResponse(agentUtils.buildChatResponse(responseText));
        listener.done();
      } catch (error) {
        listener.emitError(error instanceof Error ? error.message : String(error));
        listener.done();
      }
    }

    _normalizePrompt(req) {
      const explicitPrompt = (req && req.prompt ? req.prompt : '').trim();
      const actionPrompt =
        req && req.action && req.action.initial_user_prompt
          ? req.action.initial_user_prompt.trim()
          : '';
      const prompt = explicitPrompt || actionPrompt;

      if (!prompt || agentUtils.isCommandPrompt(prompt)) {
        return prompt;
      }
      return `/message ${prompt}`;
    }

    _getRequestConversationId(req, change) {
      return (
        (req && (req.conversation_id || req.conversationId)) ||
        this._conversationId(change)
      );
    }

    async _waitForAssistantReply(change, baselineKeys, options) {
      const config = options || {};
      const deadline = Date.now() + agentUtils.agentConfig.responseTimeoutMs;
      const pollIntervalMs =
        agentUtils.agentConfig.responsePollIntervalMs || reviewAi.config.pollIntervalMs;
      const settleMs = agentUtils.agentConfig.responseSettleMs || 0;
      let newAssistantEntries = [];
      let newAssistantEntryKeys = '';
      let stableSince = null;
      let nextPollDelayMs = 0;

      while (Date.now() < deadline) {
        if (nextPollDelayMs > 0) {
          await agentUtils.sleep(nextPollDelayMs);
        }
        const entries = await this._fetchEntries(change);
        const latestNewAssistantEntries = entries.filter(
          entry =>
            agentUtils.isAssistantEntry(entry) &&
            !baselineKeys.has(agentUtils.entryKey(entry)) &&
            !(config.excludeDynamicConfiguration && agentUtils.isDynamicConfigurationEntry(entry))
        );
        if (!latestNewAssistantEntries.length) {
          nextPollDelayMs = pollIntervalMs;
          continue;
        }

        const latestNewAssistantEntryKeys = latestNewAssistantEntries
          .map(agentUtils.entryKey)
          .join('\u0000');
        if (latestNewAssistantEntryKeys !== newAssistantEntryKeys) {
          newAssistantEntries = latestNewAssistantEntries;
          newAssistantEntryKeys = latestNewAssistantEntryKeys;
          stableSince = Date.now();
          nextPollDelayMs = Math.min(pollIntervalMs, settleMs);
          continue;
        }

        if (Date.now() - stableSince >= settleMs) {
          return agentUtils.formatAgentEntries(newAssistantEntries);
        }
        nextPollDelayMs = Math.max(
          0,
          Math.min(pollIntervalMs, settleMs - (Date.now() - stableSince))
        );
      }

      if (newAssistantEntries.length) {
        return agentUtils.formatAgentEntries(newAssistantEntries);
      }

      return (
        'ReviewAI accepted the request. The answer is still being generated and will appear ' +
        'in Gerrit comments and in the AI Assistant tab.'
      );
    }

    async _fetchEntries(change) {
      const result = await this._fetchHistory(change);
      return reviewAi.entries.fromResult(result);
    }

    _fetchHistory(change) {
      return reviewAi.api.createFetchHistory(this.plugin, this.pluginName)(change);
    }

    _sendMessage(change, message, modelId) {
      return reviewAi.api.createSendMessage(this.plugin, this.pluginName)(
        change,
        message,
        modelId,
        true
      );
    }

    _getRequestModelId(req) {
      return (
        (req && (req.model_name || req.modelName || req.model_id || req.modelId)) ||
        (req && typeof req.model === 'string' && req.model) ||
        (req && req.model && (req.model.model_id || req.model.modelId)) ||
        ''
      );
    }
  }

  Object.assign(
    ReviewAiCodeReviewProvider.prototype,
    reviewAi.agentModelMethods,
    reviewAi.agentConversationStoreMethods,
    reviewAi.agentConversationTurnMethods,
    reviewAi.agentConversationMethods
  );

  reviewAi.agent = {
    _registered: false,

    register(plugin, pluginName) {
      if (this._registered || !plugin.aiCodeReview) {
        return false;
      }

      const aiCodeReviewApi = plugin.aiCodeReview();
      if (!aiCodeReviewApi || !aiCodeReviewApi.register) {
        return false;
      }

      aiCodeReviewApi.register(new ReviewAiCodeReviewProvider(plugin, pluginName));
      this._registered = true;
      return true;
    },
  };
})(window);
