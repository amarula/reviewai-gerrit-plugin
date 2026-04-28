(function(global) {
  const reviewAi = global.ReviewAi || (global.ReviewAi = {});

  reviewAi.config = {
    pollIntervalMs: 5000,
    styles: `
      .reviewai-history {
        box-sizing: border-box;
        padding: 16px;
        color: var(--primary-text-color);
        display: grid;
        gap: 16px;
      }
      .reviewai-history__intro {
        margin: 0;
        color: var(--deemphasized-text-color);
      }
      .reviewai-history__state {
        color: var(--deemphasized-text-color);
      }
      .reviewai-history__list {
        display: grid;
        gap: 12px;
      }
      .reviewai-history__item {
        border: 1px solid var(--border-color, #ddd);
        border-radius: 8px;
        padding: 12px;
        background: var(--view-background-color, #fff);
        max-width: 70%;
      }
      .reviewai-history__item--user {
        justify-self: end;
        background: var(--table-subheader-background-color, #eef4ff);
      }
      .reviewai-history__item--assistant {
        justify-self: start;
      }
      .reviewai-history__item--system {
        justify-self: start;
        background: var(--table-subheader-background-color, #f6f7f9);
        border-style: dashed;
      }
      .reviewai-history__item--pending {
        opacity: 0.75;
      }
      .reviewai-history__meta {
        display: flex;
        flex-wrap: wrap;
        gap: 8px 16px;
        margin-bottom: 8px;
        color: var(--deemphasized-text-color);
        font-size: 0.9rem;
      }
      .reviewai-history__message {
        margin: 0;
        white-space: pre-wrap;
        line-height: 1.5;
      }
      .reviewai-history__composer {
        display: grid;
        gap: 8px;
        border-top: 1px solid var(--border-color, #ddd);
        padding-top: 16px;
      }
      .reviewai-history__composer-label {
        font-weight: 600;
      }
      .reviewai-history__composer-input {
        min-height: 1.5em;
        line-height: 1.5;
        resize: none;
        overflow-y: hidden;
        padding: 10px 12px;
        border: 1px solid var(--border-color, #ccc);
        border-radius: 8px;
        font: inherit;
        color: inherit;
        background: var(--view-background-color, #fff);
      }
      .reviewai-history__composer-actions {
        display: flex;
        align-items: center;
        gap: 12px;
      }
      .reviewai-history__composer-button {
        appearance: none;
        border: 0;
        border-radius: 999px;
        padding: 8px 14px;
        cursor: pointer;
        font: inherit;
        background: var(--primary-button-background-color, #1a73e8);
        color: var(--primary-button-text-color, #fff);
      }
      .reviewai-history__composer-button[disabled] {
        opacity: 0.6;
        cursor: default;
      }
      .reviewai-history__composer-hint {
        color: var(--deemphasized-text-color);
        font-size: 0.9rem;
      }
    `,
  };

  reviewAi.dom = {
    createElement(tagName, properties) {
      const element = document.createElement(tagName);
      Object.entries(properties || {}).forEach(([key, value]) => {
        element[key] = value;
      });
      return element;
    },

    appendChildren(parent) {
      Array.from(arguments)
        .slice(1)
        .forEach(child => parent.appendChild(child));
      return parent;
    },
  };

  reviewAi.api = {
    createFetchHistory(plugin, pluginName) {
      return change =>
        plugin.restApi().get(`/changes/${change._number}/${pluginName}~ai-review-history`);
    },

    createFetchModelInfo(plugin, pluginName) {
      return change =>
        plugin.restApi().get(`/changes/${change._number}/${pluginName}~ai-review-agent-model`);
    },

    createSendMessage(plugin, pluginName) {
      return (change, message, modelId, reviewAgent) =>
        plugin.restApi().post(`/changes/${change._number}/${pluginName}~ai-review-message`, {
          message,
          model_id: modelId,
          model_name: modelId,
          review_agent: Boolean(reviewAgent),
        });
    },
  };

  reviewAi.entries = {
    fromResult(result) {
      return Array.isArray(result && result.entries) ? result.entries : [];
    },

    buildDisplayedEntries(entries, awaitingResponse, pendingPrompt) {
      const displayedEntries = entries.slice();
      if (!awaitingResponse || !pendingPrompt) {
        return displayedEntries;
      }

      const hasPromptInEntries = entries.some(
        entry => entry.role === 'user' && entry.message === pendingPrompt
      );
      if (!hasPromptInEntries) {
        displayedEntries.push({
          role: 'user',
          author: 'You',
          message: pendingPrompt,
          pending: true,
        });
      }

      displayedEntries.push({
        role: 'assistant',
        author: 'ReviewAI',
        message: 'Thinking...',
        pending: true,
      });
      return displayedEntries;
    },

    formatLocation(entry) {
      if (entry.filename) {
        return entry.line ? `${entry.filename}:${entry.line}` : entry.filename;
      }
      if (entry.patchSet) {
        return `Patch Set ${entry.patchSet}`;
      }
      return 'Change thread';
    },

    formatReviewScore(entry) {
      const score =
        entry && entry.reviewScore !== undefined ? entry.reviewScore : entry && entry.review_score;
      if (score === null || score === undefined || String(score).trim() === '') {
        return '';
      }
      return `Code-Review ${String(score).trim()}`;
    },

    formatLocationWithReviewScore(entry) {
      const location = this.formatLocation(entry);
      const score = this.formatReviewScore(entry);
      if (!score) {
        return location;
      }
      return location && location !== 'Change thread' ? `${location} - ${score}` : score;
    },

    parseTimestamp(value) {
      if (!value) {
        return null;
      }

      const match = value.match(
        /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?$/
      );
      if (!match) {
        return null;
      }

      const milliseconds = Number((match[7] || '').slice(0, 3).padEnd(3, '0'));
      const timestamp = Date.UTC(
        Number(match[1]),
        Number(match[2]) - 1,
        Number(match[3]),
        Number(match[4]),
        Number(match[5]),
        Number(match[6]),
        milliseconds
      );

      return Number.isNaN(timestamp) ? null : new Date(timestamp);
    },

    formatTimestamp(value) {
      if (!value) {
        return '';
      }

      const timestamp = this.parseTimestamp(value);
      if (!timestamp) {
        return value.replace(/\.0+$/, '');
      }

      return timestamp.toLocaleString();
    },

    hasAssistantReplyForPendingPrompt(entries, pendingPrompt) {
      if (!pendingPrompt) {
        return false;
      }

      let promptIndex = -1;
      entries.forEach((entry, index) => {
        if (entry.role === 'user' && entry.message === pendingPrompt) {
          promptIndex = index;
        }
      });

      if (promptIndex === -1) {
        return false;
      }

      return entries
        .slice(promptIndex + 1)
        .some(entry => entry.role === 'assistant' || entry.systemMessage);
    },
  };
})(window);
