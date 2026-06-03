(function(global) {
  const reviewAi = global.ReviewAi;

  const agentConfig = {
    responseTimeoutMs: 120000,
    responsePollIntervalMs: 1000,
    responseSettleMs: 500,
  };
  const defaultActionId = 'review-change';
  const responseEntrySeparator = '\n\n---\n\n';

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

  function isDynamicConfigurationEntry(entry) {
    return /\bDYNAMIC CONFIGURATION SETTINGS\b/.test((entry && entry.message) || '');
  }

  function orderAgentEntries(entries) {
    return entries
      .map((entry, index) => ({entry, index}))
      .sort((left, right) => {
        const leftOrder = isDynamicConfigurationEntry(left.entry) ? 0 : 1;
        const rightOrder = isDynamicConfigurationEntry(right.entry) ? 0 : 1;
        return leftOrder - rightOrder || left.index - right.index;
      })
      .map(item => item.entry);
  }

  function isCommandPrompt(prompt) {
    return /^\s*\/\w+\b/.test(prompt || '');
  }

  function isDirectResponsePrompt(prompt) {
    return /^\s*\/(?:help|show)\b/.test(prompt || '');
  }

  function joinAgentResponses() {
    return Array.from(arguments)
      .map(text => (text || '').trim())
      .filter(Boolean)
      .join('\n\n');
  }

  function normalizeResponseEntrySeparators(text) {
    return String(text || '')
      .replace(/\n\n(\*\*[^*\n]+(?:\/[^*\n]+)+(?::\d+)?\*\*\n)/g, (match, header, offset, value) => {
        const textBeforeHeader = value.slice(0, offset);
        return /(?:^|\n)---$/.test(textBeforeHeader)
          ? match
          : `${responseEntrySeparator}${header}`;
      })
      .replace(/(\n\n---\n\n)(?:---\n\n)+/g, responseEntrySeparator);
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
    const orderedEntries = orderAgentEntries(entries);
    const reviewScore = orderedEntries.map(reviewAi.entries.formatReviewScore).find(Boolean);
    const messages = orderedEntries
      .map(entry =>
        formatAgentEntry(entry, {
          includeReviewScore: false,
          suppressScoredPatchSetLocation: true,
        })
      )
      .filter(Boolean);
    if (reviewScore && messages.length) {
      messages[0] = `**${reviewScore}**\n\n${messages[0]}`;
    } else if (reviewScore) {
      messages.push(`**${reviewScore}**`);
    }
    return messages.join(responseEntrySeparator);
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

  function stableUuid(value) {
    const input = String(value || '').toLowerCase();
    const parts = [
      hash32(input, 0x811c9dc5),
      hash32(input, 0x01000193),
      hash32(input, 0x85ebca6b),
      hash32(input, 0xc2b2ae35),
    ];
    const hex = parts.map(part => part.toString(16).padStart(8, '0')).join('');
    const variant = ((parseInt(hex.slice(16, 17), 16) & 0x3) | 0x8).toString(16);
    return [
      hex.slice(0, 8),
      hex.slice(8, 12),
      `4${hex.slice(13, 16)}`,
      `${variant}${hex.slice(17, 20)}`,
      hex.slice(20, 32),
    ].join('-');
  }

  function hash32(input, seed) {
    let hash = seed >>> 0;
    for (let i = 0; i < input.length; i++) {
      hash ^= input.charCodeAt(i);
      hash = Math.imul(hash, 0x01000193) >>> 0;
    }
    hash ^= hash >>> 16;
    hash = Math.imul(hash, 0x7feb352d) >>> 0;
    hash ^= hash >>> 15;
    hash = Math.imul(hash, 0x846ca68b) >>> 0;
    hash ^= hash >>> 16;
    return hash >>> 0;
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
    isDynamicConfigurationEntry,
    isCommandPrompt,
    isDirectResponsePrompt,
    joinAgentResponses,
    normalizeResponseEntrySeparators,
    formatAgentEntry,
    formatAgentEntries,
    buildClientData,
    getConversationTitle,
    stableUuid,
    toProviderDisplayName,
    emptyModelsResponse,
    emptyActionsResponse,
    canAiReview,
    configuredModels,
  };
})(window);
