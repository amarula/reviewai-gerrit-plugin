(function(global) {
  const reviewAi = global.ReviewAi;
  const agentUtils = reviewAi.agentUtils;

  class ReviewAiCodeReviewProvider {
    constructor(plugin, pluginName, serverVersion) {
      this.plugin = plugin;
      this.pluginName = pluginName;
      this.conversationTurns = new reviewAi.ReviewAgentConversationTurns(this);
      this._chatTurns = new Map();
      const version = /^(\d+)\.(\d+)(?:\.|-|$)/.exec(serverVersion || '');
      this.supports_add_context = Boolean(version &&
        (Number(version[1]) > 3 || (Number(version[1]) === 3 && Number(version[2]) >= 15)));
      this.supports_history = true;
      this.supports_more_menu = false;
      this.supports_this_change = true;
    }

    chat(req, listener) {
      this._chatAsync(req, listener);
    }

    async getContextItemTypes() {
      return reviewAi.codeContext.types();
    }

    async _chatAsync(req, listener) {
      try {
        const change = req.change;
        if (!agentUtils.getChangeNumber(change)) {
          throw new Error('ReviewAI needs a loaded Gerrit change to answer.');
        }
        const selectedContext = req.regeneration_index > 0
          ? [] : reviewAi.codeContext.selected(change, req);

        let prompt = this._normalizePrompt(req);
        if (!prompt) {
          throw new Error('Enter a message for ReviewAI.');
        }

        const conversationId = this._getRequestConversationId(req, change);
        const turnKey = `${change._number}:${conversationId}:${req.turn_index}`;
        let codeContexts;
        if (req.regeneration_index > 0) {
          let original = this._chatTurns.get(turnKey);
          if (!original) {
            const conversation = await this._getStoredConversation(change, conversationId);
            const turn = conversation && conversation.turns && conversation.turns[req.turn_index];
            if (!turn) throw new Error('Could not restore the original chat turn. Reload its history.');
            original = {prompt: turn.user_input.user_question,
              codeContexts: turn.code_contexts || (turn.code_context ? [turn.code_context] : [])};
          }
          ({prompt, codeContexts} = original);
          prompt = this._normalizePrompt({...req, prompt});
        } else {
          codeContexts = selectedContext;
          // Preserve original attachments even if sending fails before the turn can be saved.
          this._chatTurns.set(turnKey, {prompt, codeContexts});
        }
        if (codeContexts.length && !/^\/message\s+\S/.test(prompt)) {
          throw new Error('Code attachments apply to chat messages. Remove the attachments to run a command.');
        }
        const modelInfo = await this._fetchModelInfo(change);
        if (!agentUtils.canAiReview(modelInfo)) {
          throw new Error('ReviewAI is not allowed for this change.');
        }

        const baselineEntries = await this._fetchEntries(change);
        const baselineKeys = new Set(baselineEntries.map(agentUtils.entryKey));
        const requestId = this._newRequestId(change);

        const sendResult = await this._sendMessage(
          change,
          prompt,
          this._getRequestModelId(req),
          requestId,
          codeContexts
        );
        reviewAi.codeContext.consume(change, codeContexts);
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
        let responseText = !shouldWaitForAssistantReply
          ? directResponse
          : agentUtils.joinAgentResponses(
              directResponse,
              await this._waitForAssistantReply(change, sentRequestId, baselineKeys, {
                excludeDynamicConfiguration: Boolean(directResponse),
              })
            );
        responseText = reviewAi.codeContext.describe(codeContexts, responseText);
        listener.emitResponse(agentUtils.buildChatResponse(responseText));
        listener.done();
        try {
          await this.conversationTurns.storeConversationTurn(
            change,
            req,
            conversationId,
            prompt,
            responseText,
            codeContexts
          );
        } catch (error) {
          console.error('ReviewAI could not save the completed conversation turn.', error);
        }
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

        let historyResponse;
        try {
          historyResponse = await this._getNewAssistantHistoryResponse(
            change,
            baselineKeys,
            config
          );
        } catch (error) {
          if (!statusResponse) throw error;
          // History is used to enrich the reply with comment links, not to gate its delivery.
        }
        if (historyResponse) {
          return agentUtils.mergePanelResponseWithHistory(historyResponse, statusResponse);
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

    _sendMessage(change, message, modelId, requestId, codeContexts) {
      return reviewAi.api.createSendMessage(this.plugin, this.pluginName)(
        change,
        message,
        modelId,
        true,
        requestId,
        codeContexts
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

    register(plugin, pluginName, serverVersion) {
      if (this._registered || !plugin.aiCodeReview) {
        return false;
      }

      const aiCodeReviewApi = plugin.aiCodeReview();
      if (!aiCodeReviewApi || !aiCodeReviewApi.register) {
        return false;
      }

      aiCodeReviewApi.register(new ReviewAiCodeReviewProvider(plugin, pluginName, serverVersion));
      this._registered = true;
      return true;
    },
  };
})(window);
