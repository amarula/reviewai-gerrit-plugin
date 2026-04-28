(function(global) {
  const reviewAi = global.ReviewAi;
  const reviewAiDom = reviewAi.dom;
  const reviewAiEntries = reviewAi.entries;

  reviewAi.render = {
    createState(message) {
      return reviewAiDom.createElement('div', {
        className: 'reviewai-history__state',
        textContent: message,
      });
    },

    createHistoryItem(entry) {
      const role = entry.systemMessage ? 'system' : entry.role === 'assistant' ? 'assistant' : 'user';
      const pendingClass = entry.pending ? ' reviewai-history__item--pending' : '';
      const item = reviewAiDom.createElement('section', {
        className: `reviewai-history__item reviewai-history__item--${role}${pendingClass}`,
      });

      const meta = reviewAiDom.createElement('div', {className: 'reviewai-history__meta'});
      meta.appendChild(reviewAiDom.createElement('span', {textContent: entry.author || 'Unknown'}));
      meta.appendChild(
        reviewAiDom.createElement('span', {
          textContent: reviewAiEntries.formatLocation(entry),
        })
      );
      const reviewScore = reviewAiEntries.formatReviewScore(entry);
      if (reviewScore) {
        meta.appendChild(reviewAiDom.createElement('span', {textContent: reviewScore}));
      }

      if (entry.updated) {
        meta.appendChild(
          reviewAiDom.createElement('span', {
            textContent: reviewAiEntries.formatTimestamp(entry.updated),
          })
        );
      }

      const message = reviewAiDom.createElement('pre', {
        className: 'reviewai-history__message',
        textContent: entry.message || '',
      });

      return reviewAiDom.appendChildren(item, meta, message);
    },

    createHistoryBody(view) {
      if (view._canAiReview === false) {
        return this.createState('ReviewAI is not allowed for this change.');
      }

      if (view._loadingChangeNumber !== null) {
        return this.createState('Loading AI review history...');
      }

      if (view._error) {
        return this.createState('Failed to load AI review history.');
      }

      const displayedEntries = reviewAiEntries.buildDisplayedEntries(
        view._entries,
        view._awaitingResponse,
        view._pendingPrompt
      );
      if (!displayedEntries.length) {
        return this.createState('No AI chat messages were found on this change yet.');
      }

      const list = reviewAiDom.createElement('div', {className: 'reviewai-history__list'});
      displayedEntries.forEach(entry => list.appendChild(this.createHistoryItem(entry)));
      return list;
    },

    createComposer(view) {
      const composer = reviewAiDom.createElement('form', {
        className: 'reviewai-history__composer',
      });
      view._composer = composer;
      composer.addEventListener('submit', event => view._submitMessage(event));

      const label = reviewAiDom.createElement('div', {
        className: 'reviewai-history__composer-label',
        textContent: 'Message AI',
      });

      const input = reviewAiDom.createElement('textarea', {
        className: 'reviewai-history__composer-input',
        rows: 1,
        placeholder: 'Ask the AI about this change or enter /help...',
      });
      input.addEventListener('input', event => {
        view._draftMessage = event.target.value;
        view._resizeComposerInput(event.target);
      });
      input.addEventListener('keydown', event => view._handleComposerKeydown(event));

      const actions = reviewAiDom.createElement('div', {
        className: 'reviewai-history__composer-actions',
      });
      const button = reviewAiDom.createElement('button', {
        className: 'reviewai-history__composer-button',
        type: 'submit',
      });
      const hint = reviewAiDom.createElement('div', {
        className: 'reviewai-history__composer-hint',
        textContent:
          'Enter /help for command help. Replies may take a few seconds and will also appear in Gerrit comments.',
      });

      reviewAiDom.appendChildren(actions, button, hint);
      reviewAiDom.appendChildren(composer, label, input, actions);
      view._composerInput = input;
      view._composerButton = button;
      return composer;
    },

    createContainer(view) {
      const container = reviewAiDom.createElement('div', {className: 'reviewai-history'});
      container.appendChild(
        reviewAiDom.createElement('style', {textContent: reviewAi.config.styles})
      );
      container.appendChild(
        reviewAiDom.createElement('p', {
          className: 'reviewai-history__intro',
          textContent:
            'Chat with the AI by sending a patch set comment addressed to the bot. AI review comments remain in the normal Gerrit discussion.',
        })
      );
      view._content = reviewAiDom.createElement('div');
      container.appendChild(view._content);

      container.appendChild(this.createComposer(view));
      view._container = container;
      return container;
    },

    render(view) {
      if (!view._container) {
        this.createContainer(view);
      }

      if (view.firstChild !== view._container) {
        view.replaceChildren(view._container);
      }

      view._content.replaceChildren(this.createHistoryBody(view));
      if (view._composerInput.value !== view._draftMessage) {
        view._composerInput.value = view._draftMessage;
      }
      view._composer.hidden = view._canAiReview === false;
      view._composerInput.disabled = view._submitting || view._canAiReview === false;
      view._composerButton.disabled = view._submitting || view._canAiReview === false;
      view._composerButton.textContent = view._submitting ? 'Sending...' : 'Send';
      view._resizeComposerInput(view._composerInput);
    },
  };
})(window);
