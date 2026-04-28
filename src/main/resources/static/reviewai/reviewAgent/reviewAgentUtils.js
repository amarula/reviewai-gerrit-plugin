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

  reviewAi.agentUtils = {
    agentConfig,
    defaultActionId,
    buildChatResponse,
    sleep,
    getChangeNumber,
    entryKey,
    isAssistantEntry,
    isCommandPrompt,
    parseTimestampMillis,
    formatAgentEntry,
    formatAgentEntries,
    buildClientData,
    getConversationTitle,
    isSameConversationId,
    toProviderDisplayName,
    emptyModelsResponse,
    emptyActionsResponse,
    canAiReview,
    configuredModels,
  };
})(window);
