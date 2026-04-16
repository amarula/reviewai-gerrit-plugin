(function(global) {
  const reviewAi = global.ReviewAi;

  const agentConfig = {
    responseTimeoutMs: 120000,
  };

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
    return /^\s*\/(?:help|message|review|review_last|directives|forget_thread|configure|show)\b/.test(
      prompt
    );
  }

  function parseTimestampMillis(value) {
    const timestamp = reviewAi.entries.parseTimestamp(value);
    return timestamp ? timestamp.getTime() : Date.now();
  }

  function formatAgentEntry(entry) {
    const location = reviewAi.entries.formatLocation(entry);
    if (location && location !== 'Change thread') {
      return `**${location}**\n${entry.message || ''}`;
    }
    return entry.message || '';
  }

  class ReviewAiCodeReviewProvider {
    constructor(plugin, pluginName) {
      this.plugin = plugin;
      this.pluginName = pluginName;
      this.defaultModel = 'reviewai/default';
      this.supports_add_context = false;
      this.supports_history = true;
      this.supports_more_menu = false;
      this.supports_this_change = true;
    }

    async getModels() {
      return {
        models: [
          {
            model_id: this.defaultModel,
            short_text: 'ReviewAI',
            full_display_text: 'ReviewAI Gerrit assistant',
          },
        ],
        default_model_id: this.defaultModel,
        custom_actions: this._actions(),
      };
    }

    async getActions() {
      return {
        actions: this._actions(),
        default_action_id: 'ask-reviewai',
      };
    }

    chat(req, listener) {
      this._chatAsync(req, listener);
    }

    async listChatConversations(change) {
      const entries = await this._fetchEntries(change);
      if (!entries.length) {
        return [];
      }
      const lastEntry = entries[entries.length - 1];
      return [
        {
          id: this._conversationId(change),
          title: 'ReviewAI comments',
          timestamp_millis: parseTimestampMillis(lastEntry.updated),
        },
      ];
    }

    async getChatConversation(change) {
      const entries = await this._fetchEntries(change);
      return this._entriesToConversationTurns(entries);
    }

    _actions() {
      return [
        {
          id: 'ask-reviewai',
          display_text: 'Ask ReviewAI',
          hover_text: 'Ask a question about this change',
        },
        {
          id: 'review-change',
          display_text: 'Review change',
          hover_text: 'Run /review for the full change',
          enable_send_without_input: true,
          initial_user_prompt: '/review',
        },
        {
          id: 'review-last-patch-set',
          display_text: 'Review last patch set',
          hover_text: 'Run /review_last for the latest patch set',
          enable_send_without_input: true,
          initial_user_prompt: '/review_last',
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

        const prompt = this._normalizePrompt(req);
        if (!prompt) {
          throw new Error('Enter a message for ReviewAI.');
        }

        const baselineEntries = await this._fetchEntries(change);
        const baselineKeys = new Set(baselineEntries.map(entryKey));

        await this._sendMessage(change, prompt);

        const responseText = await this._waitForAssistantReply(change, baselineKeys);
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

    async _waitForAssistantReply(change, baselineKeys) {
      const deadline = Date.now() + agentConfig.responseTimeoutMs;

      while (Date.now() < deadline) {
        await sleep(reviewAi.config.pollIntervalMs);
        const entries = await this._fetchEntries(change);
        const newAssistantEntries = entries.filter(
          entry => isAssistantEntry(entry) && !baselineKeys.has(entryKey(entry))
        );
        if (newAssistantEntries.length) {
          return newAssistantEntries.map(formatAgentEntry).join('\n\n');
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

    _sendMessage(change, message) {
      return reviewAi.api.createSendMessage(this.plugin, this.pluginName)(change, message);
    }

    _entriesToConversationTurns(entries) {
      const turns = [];
      let currentTurn = null;

      entries.forEach(entry => {
        if (entry.role === 'user' && !entry.systemMessage) {
          currentTurn = {
            user_input: {
              user_question: entry.message || '',
              client_data: '',
            },
            regeneration_index: 0,
            timestamp_millis: parseTimestampMillis(entry.updated),
          };
          turns.push(currentTurn);
          return;
        }

        if (!isAssistantEntry(entry)) {
          return;
        }

        if (!currentTurn) {
          currentTurn = {
            user_input: {
              user_question: '',
              client_data: '',
            },
            regeneration_index: 0,
            timestamp_millis: parseTimestampMillis(entry.updated),
          };
          turns.push(currentTurn);
        }

        if (!currentTurn.response) {
          currentTurn.response = buildChatResponse(formatAgentEntry(entry));
        } else {
          currentTurn.response.response_parts.push({
            id: currentTurn.response.response_parts.length,
            text: formatAgentEntry(entry),
          });
        }
        currentTurn.response.timestamp_millis = parseTimestampMillis(entry.updated);
      });

      return turns;
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
