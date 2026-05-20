(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  class ReviewAiCodeReviewProvider {
    constructor(plugin, pluginName) {
      this.plugin = plugin;
      this.pluginName = pluginName;
      this.conversationTurns = new reviewAi.ReviewAgentConversationTurns(this);
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
        const requestId = this._newRequestId(change);

        const sendResult = await this._sendMessage(
          change,
          prompt,
          this._getRequestModelId(req),
          requestId
        );
        const directResponse =
          sendResult && (sendResult.response_text || sendResult.responseText);
        const sentRequestId =
          (sendResult && (sendResult.request_id || sendResult.requestId)) || requestId;
        const shouldWaitForAssistantReply =
          !agentUtils.isDirectResponsePrompt(prompt) &&
          !(
            sendResult &&
            (sendResult.wait_for_assistant_reply === false ||
              sendResult.waitForAssistantReply === false)
          );
        const responseText = !shouldWaitForAssistantReply
          ? directResponse
          : agentUtils.joinAgentResponses(
              directResponse,
              await this._waitForAssistantReply(change, sentRequestId, baselineKeys, {
                excludeDynamicConfiguration: Boolean(directResponse),
              })
            );
        await this.conversationTurns.storeConversationTurn(
          change,
          req,
          conversationId,
          prompt,
          responseText
        );
        listener.emitResponse(agentUtils.buildChatResponse(responseText));
        listener.done();
      } catch (error) {
        listener.emitResponse(
          agentUtils.buildChatResponse(error instanceof Error ? error.message : String(error))
        );
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
        this.conversationTurns.conversationId(change)
      );
    }

    async _waitForAssistantReply(change, requestId, baselineKeys, options) {
      const config = options || {};
      const deadline = Date.now() + agentUtils.agentConfig.responseTimeoutMs;
      const pollIntervalMs =
        agentUtils.agentConfig.responsePollIntervalMs || reviewAi.config.pollIntervalMs;

      while (Date.now() < deadline) {
        await agentUtils.sleep(pollIntervalMs);
        let status = null;
        try {
          status = await this._fetchMessageStatus(change, requestId);
        } catch {
          const historyResponse = await this._getNewAssistantHistoryResponse(
            change,
            baselineKeys,
            config
          );
          if (historyResponse) {
            return historyResponse;
          }
          continue;
        }
        const state = status && status.status;
        const statusResponse =
          status && (status.response_text || status.responseText);
        if (state === 'failed') {
          return statusResponse || 'ReviewAI request failed.';
        }
        if (state !== 'completed') {
          continue;
        }

        const historyResponse = await this._getNewAssistantHistoryResponse(
          change,
          baselineKeys,
          config
        );
        if (historyResponse) {
          return historyResponse;
        }
        return statusResponse || 'ReviewAI completed the request without a visible update.';
      }

      return (
        'ReviewAI accepted the request. The answer is still being generated and will appear ' +
        'in Gerrit comments.'
      );
    }

    async _getNewAssistantHistoryResponse(change, baselineKeys, config) {
      const latestNewAssistantEntries = (await this._fetchEntries(change)).filter(
        entry =>
          agentUtils.isAssistantEntry(entry) &&
          !baselineKeys.has(agentUtils.entryKey(entry)) &&
          !(
            config &&
            config.excludeDynamicConfiguration &&
            agentUtils.isDynamicConfigurationEntry(entry)
          )
      );
      return agentUtils.formatAgentEntries(latestNewAssistantEntries);
    }

    async _fetchEntries(change) {
      const result = await this._fetchHistory(change);
      return reviewAi.entries.fromResult(result);
    }

    _fetchHistory(change) {
      return reviewAi.api.createFetchHistory(this.plugin, this.pluginName)(change);
    }

    _sendMessage(change, message, modelId, requestId) {
      return reviewAi.api.createSendMessage(this.plugin, this.pluginName)(
        change,
        message,
        modelId,
        true,
        requestId
      );
    }

    _fetchMessageStatus(change, requestId) {
      return reviewAi.api.createFetchMessageStatus(this.plugin, this.pluginName)(
        change,
        requestId
      );
    }

    _newRequestId(change) {
      const changeNumber = agentUtils.getChangeNumber(change) || 'change';
      return `${changeNumber}-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
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
