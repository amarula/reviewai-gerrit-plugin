(function(global) {
  const reviewAi = global.ReviewAi;

  const agentConfig = {
    responseTimeoutMs: 120000,
  };
  const defaultActionId = 'review-change';

  function buildChatResponse(text) {
    return {
      response_parts: [{id: 0, text}],
      references: [],
      citations: [],
      timestamp_millis: Date.now(),
    };
  }

  function sleep(ms) {
    return new Promise(resolve => global.setTimeout(resolve, ms));
  }

  function getChangeNumber(change) {
    return change && change._number;
  }

  function entryKey(entry) {
    if (entry.id) {
      return entry.id;
    }
    return [
      entry.role || '',
      entry.systemMessage ? 'system' : '',
      entry.updated || '',
      entry.patchSet || '',
      entry.filename || '',
      entry.line || '',
      entry.message || '',
    ].join('\u0000');
  }

  function isAssistantEntry(entry) {
    return entry && (entry.role === 'assistant' || entry.systemMessage);
  }

  function isCommandPrompt(prompt) {
    return /^\s*\/(?:help|message|review|directives|forget_thread|configure|show)\b/.test(
      prompt
    );
  }

  function parseTimestampMillis(value) {
    const timestamp = reviewAi.entries.parseTimestamp(value);
    return timestamp ? timestamp.getTime() : Date.now();
  }

  function formatAgentEntry(entry, options) {
    const config = options || {};
    const reviewScore = reviewAi.entries.formatReviewScore(entry);
    const includeReviewScore = config.includeReviewScore !== false;
    const suppressScoredPatchSetLocation =
      config.suppressScoredPatchSetLocation && reviewScore && !entry.filename;
    const location = suppressScoredPatchSetLocation
      ? 'Change thread'
      : includeReviewScore
        ? reviewAi.entries.formatLocationWithReviewScore(entry)
        : reviewAi.entries.formatLocation(entry);
    if (location && location !== 'Change thread') {
      return `**${location}**\n${entry.message || ''}`;
    }
    return entry.message || '';
  }

  function formatAgentEntries(entries) {
    const reviewScore = entries.map(reviewAi.entries.formatReviewScore).find(Boolean);
    const messages = entries
      .map(entry =>
        formatAgentEntry(entry, {
          includeReviewScore: false,
          suppressScoredPatchSetLocation: true,
        })
      )
      .filter(Boolean);
    return (reviewScore ? [`**${reviewScore}**`].concat(messages) : messages).join('\n\n');
  }

  function buildClientData(overridesPreviousTurn) {
    if (!overridesPreviousTurn) {
      return '{}';
    }
    return JSON.stringify({
      overridesPreviousTurn: true,
      actionId: defaultActionId,
      contextItems: [],
      isBackgroundRequest: false,
    });
  }

  function getConversationTitle(prompt) {
    const normalized = (prompt || '').replace(/\s+/g, ' ').trim();
    if (!normalized) {
      return 'ReviewAI conversation';
    }
    return normalized.length > 80 ? `${normalized.slice(0, 77)}...` : normalized;
  }

  function isSameConversationId(left, right) {
    return String(left || '').toLowerCase() === String(right || '').toLowerCase();
  }

  function toDisplayName(value) {
    const knownNames = {
      openai: 'OpenAI',
      gemini: 'Gemini',
      moonshot: 'Moonshot',
    };
    const normalized = String(value || '').toLowerCase();
    if (!normalized) {
      return 'ReviewAI';
    }
    if (knownNames[normalized]) {
      return knownNames[normalized];
    }
    return normalized
      .split(/[_\s-]+/)
      .filter(Boolean)
      .map(part => part.charAt(0).toUpperCase() + part.slice(1))
      .join(' ');
  }

  function toProviderDisplayName(value) {
    const routeParts = String(value || '')
      .split('/')
      .filter(Boolean);
    return toDisplayName(routeParts.length ? routeParts[routeParts.length - 1] : value);
  }

  function emptyModelsResponse() {
    return {
      models: [],
      custom_actions: [],
    };
  }

  function emptyActionsResponse() {
    return {
      actions: [],
      default_action_id: null,
    };
  }

  function canAiReview(modelInfo) {
    return !(modelInfo && modelInfo.can_ai_review === false);
  }

  function configuredModels(modelInfo) {
    if (Array.isArray(modelInfo && modelInfo.models)) {
      return modelInfo.models;
    }
    if (!modelInfo) {
      return [];
    }
    return [
      {
        model_id: 'reviewai/default',
        provider: modelInfo.provider,
        ai_model: modelInfo.ai_model,
      },
    ];
  }

  class ReviewAiCodeReviewProvider {
    constructor(plugin, pluginName) {
      this.plugin = plugin;
      this.pluginName = pluginName;
      this.supports_add_context = false;
      this.supports_history = true;
      this.supports_more_menu = false;
      this.supports_this_change = true;
    }

    async getModels(change) {
      const modelInfo = await this._fetchModelInfo(change);
      if (!canAiReview(modelInfo)) {
        return emptyModelsResponse();
      }

      const models = configuredModels(modelInfo).map(model => {
        const provider = toProviderDisplayName(model && model.provider);
        const aiModel = model && (model.model || model.ai_model);
        const modelId =
          (model && (model.model_id || model.modelId)) || `${model.provider}/${aiModel}`;
        const fullDisplayText = aiModel ? `${provider} (${aiModel})` : provider;
        return {
          model_id: modelId,
          short_text: provider,
          full_display_text: fullDisplayText,
        };
      });

      return {
        models,
        default_model_id:
          (modelInfo && (modelInfo.default_model_id || modelInfo.defaultModelId)) ||
          (models[0] && models[0].model_id) ||
          null,
        custom_actions: this._actions(),
      };
    }

    async getActions(change) {
      if (change && getChangeNumber(change)) {
        const modelInfo = await this._fetchModelInfo(change);
        if (!canAiReview(modelInfo)) {
          return emptyActionsResponse();
        }
      }

      return {
        actions: this._actions(),
        default_action_id: defaultActionId,
      };
    }

    chat(req, listener) {
      this._chatAsync(req, listener);
    }

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
          timestamp_millis: parseTimestampMillis(lastEntry.updated),
        },
      ]);
    }

    async getChatConversation(change, conversationId) {
      if (!conversationId || !(await this._canAiReviewChange(change))) {
        return [];
      }
      const storedConversation = await this._getStoredConversation(change, conversationId);
      if (storedConversation) {
        return this._getHistoryBackedStoredTurns(change, storedConversation);
      }

      if (!isSameConversationId(conversationId, this._conversationId(change))) {
        return [];
      }

      const storedConversations = await this._listStoredConversations(change);
      const entries = await this._fetchEntries(change);
      return this._entriesToConversationTurns(
        this._filterStoredConversationEntries(change, entries, storedConversations)
      );
    }

    async _getHistoryBackedStoredTurns(change, storedConversation) {
      const storedTurns = Array.isArray(storedConversation && storedConversation.turns)
        ? storedConversation.turns
        : [];
      const storedConversationId = storedConversation && storedConversation.id;
      const includeNewTurns = isSameConversationId(
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
      } catch (error) {
        return storedTurns;
      }
    }

    _actions() {
      return [
        {
          id: 'review-change',
          display_text: 'Full Review',
          hover_text: 'Run /review for the full change',
          enable_send_without_input: true,
          initial_user_prompt: '/review',
        },
        {
          id: 'review-patchset',
          display_text: 'Review Patch Set Only',
          hover_text: 'Run /review --scope=patchset to review the patchset only',
          enable_send_without_input: true,
          initial_user_prompt: '/review --scope=patchset',
        },
        {
          id: 'review-commit-message',
          display_text: 'Review Commit Message Only',
          hover_text: 'Run /review --scope=commit_message to review the commit message only',
          enable_send_without_input: true,
          initial_user_prompt: '/review --scope=commit_message',
        },
        {
          id: 'reviewai-help',
          display_text: 'Show ReviewAI help',
          hover_text: 'Run /help to list ReviewAI commands',
          enable_send_without_input: true,
          initial_user_prompt: '/help',
        },
      ];
    }

    async _chatAsync(req, listener) {
      try {
        const change = req.change;
        if (!getChangeNumber(change)) {
          throw new Error('ReviewAI needs a loaded Gerrit change to answer.');
        }

        const modelInfo = await this._fetchModelInfo(change);
        if (!canAiReview(modelInfo)) {
          throw new Error('ReviewAI is not allowed for this change.');
        }

        const prompt = this._normalizePrompt(req);
        if (!prompt) {
          throw new Error('Enter a message for ReviewAI.');
        }

        const baselineEntries = await this._fetchEntries(change);
        const baselineKeys = new Set(baselineEntries.map(entryKey));
        const conversationId = this._getRequestConversationId(req, change);

        const sendResult = await this._sendMessage(change, prompt, this._getRequestModelId(req));
        const directResponse =
          sendResult && (sendResult.response_text || sendResult.responseText);
        const responseText =
          directResponse || (await this._waitForAssistantReply(change, baselineKeys));
        await this._storeConversationTurn(change, req, conversationId, prompt, responseText);
        listener.emitResponse(buildChatResponse(responseText));
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

      if (!prompt || isCommandPrompt(prompt)) {
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

    async _waitForAssistantReply(change, baselineKeys) {
      const deadline = Date.now() + agentConfig.responseTimeoutMs;

      while (Date.now() < deadline) {
        await sleep(reviewAi.config.pollIntervalMs);
        const entries = await this._fetchEntries(change);
        const newAssistantEntries = entries.filter(
          entry => isAssistantEntry(entry) && !baselineKeys.has(entryKey(entry))
        );
        if (newAssistantEntries.length) {
          return formatAgentEntries(newAssistantEntries);
        }
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

    async _canAiReviewChange(change) {
      const modelInfo = await this._fetchModelInfo(change);
      return canAiReview(modelInfo);
    }

    _conversationStore(change, input) {
      return this.plugin
        .restApi()
        .post(`/changes/${change._number}/${this.pluginName}~ai-review-agent-conversations`, input);
    }

    async _fetchModelInfo(change) {
      if (!change || !change._number) {
        return null;
      }
      return this.plugin
        .restApi()
        .get(`/changes/${change._number}/${this.pluginName}~ai-review-agent-model`);
    }

    async _listStoredConversations(change) {
      const output = await this._conversationStore(change, {action: 'list'});
      return Array.isArray(output && output.conversations) ? output.conversations : [];
    }

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
    }

    async _appendStoredConversationTurn(change, input) {
      return this._conversationStore(change, {
        action: 'append',
        ...input,
      });
    }

    _hasReviewAiCommentsConversation(change, conversations) {
      const conversationId = this._conversationId(change);
      return conversations.some(conversation =>
        isSameConversationId(conversation && conversation.id, conversationId)
      );
    }

    _filterStoredConversationEntries(change, entries, conversations, ignoredConversationId) {
      const conversationId = this._conversationId(change);
      const userMessages = new Map();
      const assistantMessages = new Map();

      conversations.forEach(conversation => {
        if (
          !conversation ||
          isSameConversationId(conversation.id, conversationId) ||
          isSameConversationId(conversation.id, ignoredConversationId)
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
        if (isAssistantEntry(entry)) {
          return !this._consumeMessageCount(assistantMessages, formatAgentEntry(entry));
        }
        return true;
      });
    }

    _turnResponseText(turn) {
      const response = turn && (turn.response || turn.chat_response);
      const responseParts = response && response.response_parts;
      if (!Array.isArray(responseParts)) {
        return '';
      }
      return responseParts.map(part => (part && part.text) || '').join('\n\n');
    }

    _turnUserQuestion(turn) {
      const userInput = turn && turn.user_input;
      return (userInput && userInput.user_question) || '';
    }

    _mergeStoredTurnsWithHistory(storedTurns, historyTurns, includeNewTurns) {
      if (!historyTurns.length) {
        return storedTurns;
      }

      const mergedTurns = storedTurns.slice();
      historyTurns.forEach(historyTurn => {
        const storedTurnIndex = mergedTurns.findIndex(
          storedTurn => this._turnUserQuestion(storedTurn) === this._turnUserQuestion(historyTurn)
        );
        if (storedTurnIndex !== -1) {
          mergedTurns[storedTurnIndex] = this._mergeTurnWithHistory(
            mergedTurns[storedTurnIndex],
            historyTurn
          );
        } else if (includeNewTurns) {
          mergedTurns.push(historyTurn);
        }
      });
      return mergedTurns;
    }

    _mergeTurnWithHistory(storedTurn, historyTurn) {
      const refreshedResponse = historyTurn.response || historyTurn.chat_response;
      return {
        ...storedTurn,
        response: refreshedResponse || storedTurn.response,
        chat_response: refreshedResponse || storedTurn.chat_response,
        timestamp_millis: historyTurn.timestamp_millis || storedTurn.timestamp_millis,
      };
    }

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
    }

    _incrementMessageCount(messages, message) {
      if (!message) {
        return;
      }
      messages.set(message, (messages.get(message) || 0) + 1);
    }

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
    }

    _entriesToConversationTurns(entries) {
      const turns = [];
      let currentTurn = null;
      let hasClientDataOverride = false;

      entries.forEach(entry => {
        if (entry.role === 'user' && !entry.systemMessage) {
          currentTurn = {
            user_input: {
              user_question: entry.message || '',
              client_data: buildClientData(!hasClientDataOverride),
            },
            regeneration_index: 0,
            timestamp_millis: parseTimestampMillis(entry.updated),
          };
          turns.push(currentTurn);
          hasClientDataOverride = true;
          return;
        }

        if (!isAssistantEntry(entry)) {
          return;
        }

        if (!currentTurn) {
          currentTurn = {
            user_input: {
              user_question: '',
              client_data: buildClientData(!hasClientDataOverride),
            },
            regeneration_index: 0,
            timestamp_millis: parseTimestampMillis(entry.updated),
          };
          turns.push(currentTurn);
          hasClientDataOverride = true;
        }

        const reviewScore = reviewAi.entries.formatReviewScore(entry);
        const entryText = formatAgentEntry(entry, {
          includeReviewScore: false,
          suppressScoredPatchSetLocation: true,
        });

        if (!currentTurn.response) {
          currentTurn.response = buildChatResponse(entryText);
        } else {
          currentTurn.response.response_parts.push({
            id: currentTurn.response.response_parts.length,
            text: entryText,
          });
        }
        this._applyReviewScoreToTurn(currentTurn, reviewScore);
        currentTurn.response.timestamp_millis = parseTimestampMillis(entry.updated);
      });

      return turns;
    }

    async _storeConversationTurn(change, req, conversationId, prompt, responseText) {
      const now = Date.now();
      const turn = {
        user_input: {
          user_question: prompt,
          client_data:
            (req && req.client_data) || buildClientData(!req || req.turn_index === 0),
        },
        response: buildChatResponse(responseText),
        regeneration_index: (req && req.regeneration_index) || 0,
        timestamp_millis: now,
      };
      await this._appendStoredConversationTurn(change, {
        conversationId,
        conversation_id: conversationId,
        title: getConversationTitle(prompt),
        timestampMillis: now,
        timestamp_millis: now,
        turnIndex: req && Number.isInteger(req.turn_index) ? req.turn_index : undefined,
        turn_index: req && Number.isInteger(req.turn_index) ? req.turn_index : undefined,
        turn,
      });
    }

    _conversationId(change) {
      return `reviewai-${getChangeNumber(change) || 'change'}`;
    }
  }

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
