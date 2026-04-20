(function(global) {
  const reviewAi = global.ReviewAi;

  class ReviewAiTabHeader extends HTMLElement {
    connectedCallback() {
      this.textContent = 'AI Assistant';
    }
  }

  class ReviewAiTabContent extends HTMLElement {
    constructor() {
      super();
      this._change = null;
      this._fetchHistory = null;
      this._fetchModelInfo = null;
      this._sendMessage = null;
      this._loadedChangeNumber = null;
      this._loadingChangeNumber = null;
      this._entries = [];
      this._error = null;
      this._canAiReview = true;
      this._submitting = false;
      this._pollHandle = null;
      this._draftMessage = '';
      this._awaitingResponse = false;
      this._pendingPrompt = null;
      this._lastPollMs = 0;
      this._container = null;
      this._content = null;
      this._composerInput = null;
      this._composerButton = null;

      this._upgradeProperty('change');
      this._upgradeProperty('fetchHistory');
      this._upgradeProperty('fetchModelInfo');
      this._upgradeProperty('sendMessage');
    }

    connectedCallback() {
      this._render();
      this._loadIfReady();
      this._pollHandle = window.setInterval(() => this._poll(), reviewAi.config.pollIntervalMs);
    }

    disconnectedCallback() {
      if (this._pollHandle !== null) {
        window.clearInterval(this._pollHandle);
        this._pollHandle = null;
      }
    }

    set change(change) {
      this._change = change;
      this._loadedChangeNumber = null;
      this._error = null;
      this._canAiReview = true;
      this._draftMessage = '';
      this._awaitingResponse = false;
      this._pendingPrompt = null;
      this._loadIfReady();
    }

    set fetchHistory(fetchHistory) {
      this._fetchHistory = fetchHistory;
      this._loadIfReady();
    }

    set fetchModelInfo(fetchModelInfo) {
      this._fetchModelInfo = fetchModelInfo;
      this._loadedChangeNumber = null;
      this._loadIfReady();
    }

    set sendMessage(sendMessage) {
      this._sendMessage = sendMessage;
    }

    async _loadIfReady() {
      const changeNumber = this._getChangeNumber();
      if (!this.isConnected || !this._fetchHistory || !changeNumber) {
        return;
      }
      if (
        this._loadingChangeNumber === changeNumber ||
        this._loadedChangeNumber === changeNumber
      ) {
        return;
      }

      if (!(await this._refreshCapability(changeNumber))) {
        return;
      }

      await this._refreshHistory({
        changeNumber,
        showLoadingState: true,
        resetEntries: true,
      });
    }

    async _refreshCapability(changeNumber) {
      if (!this._fetchModelInfo) {
        this._canAiReview = true;
        return true;
      }

      try {
        const modelInfo = await this._fetchModelInfo(this._change);
        if (this._getChangeNumber() !== changeNumber) {
          return false;
        }

        this._setCanAiReview(changeNumber, !(modelInfo && modelInfo.can_ai_review === false));
        return this._canAiReview;
      } catch (error) {
        this._canAiReview = true;
        return true;
      }
    }

    _setCanAiReview(changeNumber, canAiReview) {
      this._canAiReview = canAiReview;
      if (!this._canAiReview) {
        this._entries = [];
        this._loadedChangeNumber = changeNumber;
        this._error = null;
        this._render();
      }
    }

    async _submitMessage(event) {
      event.preventDefault();
      if (!this._sendMessage || !this._change || !this._canAiReview || this._submitting) {
        return;
      }

      const input = this._composerInput;
      const text = input ? input.value.trim() : '';
      if (!text) {
        return;
      }

      this._submitting = true;
      this._error = null;
      this._render();

      try {
        await this._sendMessage(this._change, text);
        this._awaitingResponse = true;
        this._pendingPrompt = text;
        this._draftMessage = '';
        if (input) {
          input.value = '';
        }
        this._loadedChangeNumber = null;
        await this._loadIfReady();
      } catch (error) {
        this._error = error;
      } finally {
        this._submitting = false;
        this._render();
      }
    }

    async _poll() {
      if (
        !this.isConnected ||
        this._loadingChangeNumber !== null ||
        this._submitting ||
        this._draftMessage.trim()
      ) {
        return;
      }

      const changeNumber = this._getChangeNumber();
      if (!this._fetchHistory || !changeNumber || !this._canAiReview) {
        return;
      }

      const now = Date.now();
      if (!this._awaitingResponse && now - this._lastPollMs < reviewAi.config.pollIntervalMs) {
        return;
      }

      await this._refreshHistory({changeNumber});
    }

    async _refreshHistory(state) {
      const changeNumber = state.changeNumber;
      const showLoadingState = state.showLoadingState || false;
      const resetEntries = state.resetEntries || false;

      if (showLoadingState) {
        this._loadingChangeNumber = changeNumber;
        if (resetEntries) {
          this._entries = [];
        }
        this._error = null;
        this._render();
      }

      const requestedChange = this._change;
      try {
        const result = await this._fetchHistory(requestedChange);
        if (this._getChangeNumber() !== changeNumber) {
          return;
        }
        if (!this._canAiReview) {
          return;
        }

        const entries = reviewAi.entries.fromResult(result);
        this._entries = entries;
        if (
          this._awaitingResponse &&
          reviewAi.entries.hasAssistantReplyForPendingPrompt(entries, this._pendingPrompt)
        ) {
          this._awaitingResponse = false;
          this._pendingPrompt = null;
        }
        this._loadedChangeNumber = changeNumber;
        this._lastPollMs = Date.now();
        this._error = null;
      } catch (error) {
        if (this._getChangeNumber() !== changeNumber) {
          return;
        }
        this._error = error;
      } finally {
        if (this._loadingChangeNumber === changeNumber) {
          this._loadingChangeNumber = null;
        }
        this._render();
      }
    }

    _getChangeNumber() {
      return this._change && this._change._number;
    }

    _upgradeProperty(propertyName) {
      if (!Object.prototype.hasOwnProperty.call(this, propertyName)) {
        return;
      }

      const value = this[propertyName];
      delete this[propertyName];
      this[propertyName] = value;
    }

    _resizeComposerInput(input) {
      if (!input) {
        return;
      }
      input.style.height = 'auto';
      input.style.height = `${input.scrollHeight}px`;
    }

    _handleComposerKeydown(event) {
      if (event.key !== 'Enter' || event.shiftKey) {
        return;
      }
      event.preventDefault();
      event.target.form.requestSubmit();
    }

    _render() {
      reviewAi.render.render(this);
    }
  }

  reviewAi.ReviewAiTabHeader = ReviewAiTabHeader;
  reviewAi.ReviewAiTabContent = ReviewAiTabContent;
})(window);
