(function(global) {
  const reviewAi = global.ReviewAi;
  const maxFileBytes = 64 * 1024;
  const maxAttachments = 8;
  const fileType = 'reviewai-changed-file';
  const menuTitle = 'Changed file';
  const snapshots = new WeakMap();
  const views = new Set();

  function parseReference(reference) {
    const match = /^reviewai-file:([1-9]\d*):([0-9a-f]{40}):([^\s#]+)(?:#L([1-9]\d*)-L([1-9]\d*))?$/.exec(reference || '');
    if (!match) return null;
    try {
      const path = decodeURIComponent(match[3]);
      if (!path || path.startsWith('/') || path.includes('\0') ||
          path.split('/').some(part => !part || part === '.' || part === '..')) return null;
      const file = {change: Number(match[1]), revision: match[2], path};
      if (match[4]) {
        file.start_line = Number(match[4]);
        file.end_line = Number(match[5]);
        if (!Number.isSafeInteger(file.start_line) || !Number.isSafeInteger(file.end_line) ||
            file.end_line < file.start_line) return null;
      }
      return file;
    } catch {
      return null;
    }
  }

  function toContextItem(reference) {
    const file = parseReference(reference);
    const range = file?.start_line ? `:${file.start_line}–${file.end_line}` : '';
    return file ? {
      type_id: fileType,
      identifier: reference,
      link: '',
      title: `${file.path}${range} @ ${file.revision.slice(0, 8)}`,
      tooltip: `${range ? `Lines ${file.start_line}–${file.end_line}` : 'Whole file'} at revision ${file.revision}`,
    } : undefined;
  }

  function revisionId(change, revision) {
    if (!change) return null;
    if (!revision) return change.current_revision;
    const match = Object.entries(change.revisions || {}).find(
      ([, info]) => info._number === revision._number
    );
    return (match && match[0]) || (revision.commit && revision.commit.commit);
  }

  class ReviewAiCodeContext extends HTMLElement {
    constructor() {
      super();
      this.attachShadow({mode: 'open'}).innerHTML = `
        <style>
          :host { display: contents; }
          #picker { position: fixed; inset: auto; margin: 0; padding: 16px;
            box-sizing: border-box; max-height: calc(100dvh - 24px); overflow: auto;
            color: var(--primary-text-color, #222);
            font: inherit; background: var(--dialog-background-color, #fff);
            border: 1px solid var(--border-color, #ccc); border-radius: 12px;
            box-shadow: 0 8px 28px #0003; }
          h3 { margin: 0 0 12px; font-size: 14px; }
          label { display: block; margin-bottom: 6px; }
          select { box-sizing: border-box; width: 100%; padding: 6px;
            color: var(--primary-text-color, #222);
            background: var(--background-color-primary, #fff); }
          button { cursor: pointer; padding: 5px 10px; }
          button:disabled { cursor: default; }
          #actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 14px; }
          #status, #attachment-count { margin-top: 8px; font-size: var(--font-size-small, 12px); }
          #range-option { margin-top: 12px; }
          #range-fields { border: 0; margin: 12px 0 0; padding: 0; }
          #range-fields[hidden] { display: none; }
          #line-inputs { display: flex; gap: 16px; margin-bottom: 8px; }
          #line-inputs label { flex: 1; }
          input[type=number] { box-sizing: border-box; width: 100%; padding: 5px;
            color: var(--primary-text-color, #222);
            background: var(--background-color-primary, #fff); }
          #preview-container { display: flex; height: 200px; border: 1px solid var(--border-color, #ccc); }
          #line-numbers, #preview { margin: 0; padding: 6px; font: 12px/18px monospace;
            color: var(--primary-text-color, #222);
            background: var(--background-color-primary, #fff); tab-size: 4; }
          #line-numbers { overflow: hidden; text-align: right; user-select: none; flex-shrink: 0; }
          #preview { min-width: 0; flex: 1; border: 0; resize: none; white-space: pre; }
          #range-status { margin-top: 8px; font-size: var(--font-size-small, 12px); }
        </style>
        <section id="picker" popover="auto" role="dialog" aria-labelledby="title">
          <h3 id="title">Attach code</h3>
          <label for="files">Changed file</label>
          <select id="files" aria-label="Changed file for code context" disabled></select>
          <div id="status" role="status"></div>
          <div id="attachment-count" role="status"></div>
          <label id="range-option"><input id="range-toggle" type="checkbox" disabled> Select lines</label>
          <fieldset id="range-fields" hidden disabled>
            <legend>Code selection</legend>
            <div id="line-inputs">
              <label>Start line<input id="start-line" type="number" min="1" step="1"></label>
              <label>End line<input id="end-line" type="number" min="1" step="1"></label>
            </div>
            <div id="preview-container">
              <pre id="line-numbers" aria-hidden="true"></pre>
              <textarea id="preview" readonly wrap="off" spellcheck="false" aria-label="File preview"></textarea>
            </div>
          </fieldset>
          <div id="range-status" role="status" hidden></div>
          <div id="actions">
            <button id="finish" type="button">Finish</button>
            <button id="attach" type="button" disabled>Attach file</button>
          </div>
        </section>`;
      this._popover = this.shadowRoot.querySelector('#picker');
      this._select = this.shadowRoot.querySelector('#files');
      this._attachButton = this.shadowRoot.querySelector('#attach');
      this._rangeToggle = this.shadowRoot.querySelector('#range-toggle');
      this._rangeFields = this.shadowRoot.querySelector('#range-fields');
      this._rangeStatus = this.shadowRoot.querySelector('#range-status');
      this._startLine = this.shadowRoot.querySelector('#start-line');
      this._endLine = this.shadowRoot.querySelector('#end-line');
      this._preview = this.shadowRoot.querySelector('#preview');
      this._lineNumbers = this.shadowRoot.querySelector('#line-numbers');
      this._previewGeneration = 0;
      this._select.addEventListener('change', () => this._fileChanged());
      this._rangeToggle.addEventListener('change', () => this._toggleRange());
      this._startLine.addEventListener('input', () => this._updateRange(true));
      this._endLine.addEventListener('input', () => this._updateRange(true));
      this._preview.addEventListener('select', () => {
        if (this.shadowRoot.activeElement === this._preview) this._selectCode();
      });
      this._preview.addEventListener('scroll', () => {
        this._lineNumbers.scrollTop = this._preview.scrollTop;
      });
      this._attachButton.addEventListener('click', () => this._attach());
      this.shadowRoot.querySelector('#finish').addEventListener('click', () => {
        this._close();
        this._anchor?.focus();
      });
      this._onMenuClick = event => this._handleMenuClick(event);
      this._positionPopover = () => this._position();
      this._observer = new MutationObserver(() => this._connectNativeMenu());
      this._watched = new Set();
      this._generation = 0;
      this._popover.addEventListener('toggle', event => {
        if (event.newState === 'open') {
          global.addEventListener('resize', this._positionPopover);
          global.addEventListener('scroll', this._positionPopover, true);
        } else {
          global.removeEventListener('resize', this._positionPopover);
          global.removeEventListener('scroll', this._positionPopover, true);
        }
      });
    }

    set change(value) {
      this._change = value;
      this._scheduleLoad();
    }

    set revision(value) {
      this._revision = value;
      this._scheduleLoad();
    }

    connectedCallback() {
      views.add(this);
      this._scheduleLoad();
      this._connectNativeMenu();
      customElements.whenDefined('chat-panel').then(() => {
        if (this.isConnected) this._connectNativeMenu();
      });
    }

    disconnectedCallback() {
      views.delete(this);
      this._contextChips().forEach(chip => this._removeItem(chip.contextItem));
      this._close();
      this._observer.disconnect();
      this._watched.clear();
      this._nativeRoot?.removeEventListener('click', this._onMenuClick, true);
      this._nativeRoot = null;
      this._input = null;
      this._generation++;
      this._previewGeneration++;
      global.removeEventListener('resize', this._positionPopover);
      global.removeEventListener('scroll', this._positionPopover, true);
      this._key = null;
    }

    _scheduleLoad() {
      if (this._scheduled) return;
      this._scheduled = true;
      queueMicrotask(() => {
        this._scheduled = false;
        if (this.isConnected) this._syncScope();
      });
    }

    _syncScope() {
      const sha = revisionId(this._change, this._revision);
      const changeNumber = this._change && this._change._number;
      const key = `${changeNumber}:${sha}`;
      if (key === this._key) return;
      this._close();
      this._generation++;
      this._previewGeneration++;
      this._key = key;
      this._sha = sha;
      for (const chip of this._contextChips()) {
        const file = parseReference(chip.contextItem.identifier);
        if (!file || file.change !== changeNumber || file.revision !== sha) {
          this._removeItem(chip.contextItem);
        }
      }
    }

    // Gerrit 3.15 has no public picker callback. Keep the native menu adaptation here:
    // intercept only our provider-supplied item, then use its normal chip events.
    _connectNativeMenu() {
      const endpoint = this.parentElement;
      const panel = endpoint?.querySelector('chat-panel');
      const panelRoot = panel?.shadowRoot;
      const promptRoot = panelRoot?.querySelector('prompt-box')?.shadowRoot;
      const input = promptRoot?.querySelector('context-input-chip');
      const roots = [endpoint, panelRoot, promptRoot, input?.shadowRoot].filter(Boolean);
      if (roots.length !== this._watched.size || roots.some(root => !this._watched.has(root))) {
        this._observer.disconnect();
        this._watched = new Set(roots);
        roots.forEach(root => this._observer.observe(root, {childList: true, subtree: true}));
      }
      if (input?.shadowRoot !== this._nativeRoot) {
        this._close();
        this._nativeRoot?.removeEventListener('click', this._onMenuClick, true);
        this._input = input;
        this._nativeRoot = input?.shadowRoot;
        this._promptRoot = promptRoot;
        this._nativeRoot?.addEventListener('click', this._onMenuClick, true);
      }
      // Restored turns retain their chips for history, but attachments are one-shot drafts.
      this._contextChips().filter(chip => chip.contextItem.reviewai_consumed)
        .forEach(chip => this._removeItem(chip.contextItem));
      if (this._popover.matches(':popover-open')) {
        this._updateRange();
        this._position();
      }
    }

    _handleMenuClick(event) {
      const menu = this._nativeRoot?.querySelector('#contextMenu');
      const item = event.composedPath().find(node => node.localName === 'md-menu-item');
      if (!item || !menu?.contains(item)) return;
      const index = Array.from(menu.querySelectorAll('md-menu-item')).indexOf(item);
      if (this._input.contextMenuItems?.[index]?.id !== fileType) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      menu.open = false;
      this._anchor = this._nativeRoot.querySelector('#addContextChip');
      this._syncScope();
      this._popover.showPopover();
      this._position();
      this._load();
    }

    _close() {
      if (this._popover.matches(':popover-open')) this._popover.hidePopover();
    }

    _position() {
      if (!this._popover.matches(':popover-open')) return;
      if (!this._anchor?.isConnected) {
        this._close();
        return;
      }
      const anchor = this._anchor.getBoundingClientRect();
      const width = Math.min(this._rangeToggle.checked ? 640 : 360, global.innerWidth - 24);
      this._popover.style.width = `${width}px`;
      const height = this._popover.getBoundingClientRect().height;
      const above = anchor.top - height - 8;
      const top = above >= 12 ? above : Math.min(anchor.bottom + 8, global.innerHeight - height - 12);
      this._popover.style.left = `${Math.max(12, Math.min(anchor.left, global.innerWidth - width - 12))}px`;
      this._popover.style.top = `${Math.max(12, top)}px`;
    }

    _contextChips() {
      return Array.from(this._promptRoot?.querySelectorAll('context-chip.external-context') || [])
        .filter(chip => chip.contextItem?.type_id === fileType);
    }

    _removeItem(item) {
      const chip = this._contextChips().find(candidate => candidate.contextItem === item);
      chip?.dispatchEvent(new CustomEvent('remove-context-chip', {bubbles: true, composed: true}));
    }

    _reference() {
      const range = this._rangeToggle.checked
        ? `#L${this._startLine.valueAsNumber}-L${this._endLine.valueAsNumber}` : '';
      return `reviewai-file:${this._change?._number}:${this._sha}:${encodeURIComponent(this._select.value)}${range}`;
    }

    _attach() {
      if (this._attachButton.disabled || !this._input?.isConnected) return;
      const item = toContextItem(this._reference());
      if (!item) return;
      const chips = this._contextChips();
      if (!chips.some(chip => chip.contextItem.identifier === item.identifier)) {
        if (chips.length >= maxAttachments) return;
        this._input.dispatchEvent(new CustomEvent('context-item-added', {
          detail: item, bubbles: true, composed: true,
        }));
      }
    }

    _fileChanged(existing) {
      this._previewGeneration++;
      this._lineStarts = null;
      this._preview.value = '';
      this._lineNumbers.textContent = '';
      this._rangeToggle.checked = Boolean(existing?.start_line && this._select.value);
      this._rangeToggle.disabled = this._select.disabled || !this._select.value;
      this._startLine.value = existing?.start_line || '';
      this._endLine.value = existing?.end_line || '';
      this._toggleRange();
    }

    _toggleRange() {
      const ranged = this._rangeToggle.checked;
      this._rangeFields.hidden = !ranged;
      this._rangeStatus.hidden = !ranged;
      this._attachButton.textContent = ranged ? 'Attach selection' : 'Attach file';
      if (ranged && !this._lineStarts) this._loadPreview();
      else this._updateRange();
      this._position();
    }

    async _loadPreview() {
      const generation = ++this._previewGeneration;
      const path = this._select.value;
      this._rangeFields.disabled = true;
      this._attachButton.disabled = true;
      this._rangeStatus.textContent = 'Loading file preview…';
      try {
        const response = await this.plugin.restApi().fetch('GET',
          `/changes/${this._change._number}/revisions/${this._sha}/files/${encodeURIComponent(path)}/content`
        );
        if (!response?.ok) throw new Error('Could not load file preview.');
        let encoded = await response.text();
        if (!this.isConnected || generation !== this._previewGeneration) return;
        if (response.headers.get('Content-Type')?.includes('application/json')) {
          encoded = JSON.parse(encoded.replace(/^\)\]\}'\s*/, ''));
        }
        if (typeof encoded !== 'string' || encoded.length > Math.ceil(maxFileBytes / 3) * 4) {
          throw new Error('File preview exceeds 64 KiB.');
        }
        const bytes = Uint8Array.from(atob(encoded), character => character.charCodeAt(0));
        if (bytes.length > maxFileBytes || bytes.includes(0)) {
          throw new Error('Select a UTF-8 text file up to 64 KiB.');
        }
        const content = new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(bytes)
          .replace(/\r\n?/g, '\n');
        this._preview.value = content;
        this._lineStarts = [];
        for (let index = 0; index < content.length; index++) {
          if (index === 0 || content[index - 1] === '\n') this._lineStarts.push(index);
        }
        const count = this._lineStarts.length;
        this._lineNumbers.textContent = this._lineStarts.map((_, index) => index + 1).join('\n');
        this._startLine.max = this._endLine.max = count;
        this._startLine.value ||= count ? 1 : '';
        this._endLine.value ||= count || '';
        this._rangeFields.disabled = count === 0;
        this._updateRange(true);
      } catch (error) {
        if (this.isConnected && generation === this._previewGeneration) {
          this._rangeStatus.textContent = error instanceof Error ? error.message : 'Could not load file preview.';
          this._updateRange();
        }
      }
      this._position();
    }

    _updateRange(highlight = false) {
      const ranged = this._rangeToggle.checked;
      const count = this._lineStarts?.length || 0;
      const start = this._startLine.valueAsNumber;
      const end = this._endLine.valueAsNumber;
      const valid = count > 0 && Number.isInteger(start) && Number.isInteger(end) &&
        start >= 1 && end >= start && end <= count;
      const chips = this._contextChips();
      const full = chips.length >= maxAttachments;
      const duplicate = chips.some(chip => chip.contextItem.identifier === this._reference());
      this.shadowRoot.querySelector('#attachment-count').textContent =
        `${chips.length} of ${maxAttachments} attachments.${full ? ' Remove a chip to add another.' : ''}`;
      this._attachButton.disabled = this._select.disabled || !this._select.value ||
        (ranged && !valid) || (full && !duplicate);
      if (!ranged || !this._lineStarts) return;
      this._rangeStatus.textContent = !count ? 'This file has no lines to select.' : valid
        ? `Lines ${start}–${end} of ${count}. Select text in the preview or enter line numbers.`
        : `Enter a start and end line between 1 and ${count}, with the end at or after the start.`;
      if (valid && highlight) {
        this._preview.setSelectionRange(this._lineStarts[start - 1],
          this._lineStarts[end] ?? this._preview.value.length);
      }
    }

    _selectCode() {
      if (!this._lineStarts?.length) return;
      const {selectionStart: start, selectionEnd: end, value} = this._preview;
      const last = end > start && value[end - 1] === '\n' ? end - 1 : end;
      const lineAt = offset => Math.min(this._lineStarts.length, value.slice(0, offset).split('\n').length);
      this._startLine.value = lineAt(start);
      this._endLine.value = lineAt(last);
      this._updateRange();
    }

    async _load() {
      const { _sha: sha, _key: key } = this;
      const changeNumber = this._change?._number;
      const generation = ++this._generation;
      this._select.replaceChildren(new Option('Choose a changed file…', ''));
      this._select.disabled = true;
      this._attachButton.disabled = true;
      this._fileChanged();
      const status = this.shadowRoot.querySelector('#status');
      if (!changeNumber || !sha) {
        status.textContent = 'Load a patch set to attach code.';
        return;
      }
      status.textContent = 'Loading changed files…';
      try {
        const [files, modelInfo] = await Promise.all([
          this.plugin.restApi().get(`/changes/${changeNumber}/revisions/${sha}/files/`),
          this.plugin.restApi().get(
            `/changes/${changeNumber}/${this.plugin.getPluginName()}~ai-review-agent-model`
          ),
        ]);
        if (!this.isConnected || this._key !== key || generation !== this._generation) return;
        if (!reviewAi.agentUtils.canAiReview(modelInfo)) {
          status.textContent = 'ReviewAI is not allowed for this change.';
          return;
        }
        let count = 0;
        Object.entries(files || {}).sort(([a], [b]) => a.localeCompare(b)).forEach(([path, file]) => {
          if (path.startsWith('/') || file.status === 'D' || file.binary ||
              file.size > maxFileBytes ||
              (file.new_mode != null && (file.new_mode & 0o170000) !== 0o100000)) return;
          this._select.add(new Option(path, path));
          count++;
        });
        this._select.disabled = count === 0;
        const existing = this._contextChips().map(chip => parseReference(chip.contextItem.identifier))
          .find(file => file?.change === changeNumber && file.revision === sha);
        this._select.value = existing?.path || '';
        this._fileChanged(existing);
        status.textContent = count
          ? 'Text files up to 64 KiB. Deleted and binary files are unavailable.'
          : 'No eligible changed text files in this patch set.';
        this._position();
        if (this._popover.matches(':popover-open')) this._select.focus();
      } catch (error) {
        if (this.isConnected && this._key === key && generation === this._generation) {
          status.textContent = error instanceof Error ? error.message : 'Could not load changed files.';
          this._position();
        }
      }
    }
  }

  reviewAi.codeContext = {
    typeId: fileType,
    maxAttachments,

    register(plugin) {
      if (!customElements.get('reviewai-code-context')) {
        customElements.define('reviewai-code-context', ReviewAiCodeContext);
      }
      plugin.registerCustomComponent('chat-panel', 'reviewai-code-context');
    },

    types() {
      return [{
        id: fileType,
        name: menuTitle,
        icon: 'description',
        regex: /reviewai-file:[1-9]\d*:[0-9a-f]{40}:[^\s#]+(?:#L[1-9]\d*-L[1-9]\d*)?/g,
        placeholder: 'reviewai-file:change:revision:path',
        parse: toContextItem,
      }];
    },

    selected(change, request) {
      const items = (request?.external_contexts || [])
        .filter(item => item.type_id === fileType && !item.reviewai_consumed);
      if (items.length > maxAttachments) {
        throw new Error(`Attach up to ${maxAttachments} files or ranges per message. Remove the extra attachments.`);
      }
      const unique = new Map();
      for (const item of items) {
        const file = parseReference(item.identifier);
        if (!file || file.change !== change._number) {
          throw new Error('The attached file does not belong to this change. Remove it and select it again.');
        }
        const attachment = {revision: file.revision, path: file.path};
        if (file.start_line) {
          attachment.start_line = file.start_line;
          attachment.end_line = file.end_line;
        }
        unique.set(JSON.stringify(attachment), attachment);
      }
      const attachments = Array.from(unique.values());
      snapshots.set(attachments, items);
      return attachments;
    },

    consume(change, attachments) {
      const items = attachments && snapshots.get(attachments);
      if (!items) return;
      views.forEach(view => {
        if (view._change?._number === change._number) items.forEach(item => view._removeItem(item));
      });
    },

    describe(attachments, response) {
      if (!attachments?.length) return response;
      const descriptions = attachments.map(file => {
        const path = file.path.replace(/([\\`*_{}[\]()<>])/g, '\\$1');
        const range = file.start_line ? `, lines ${file.start_line}–${file.end_line}` : '';
        return `${path} (${file.revision.slice(0, 8)}${range})`;
      });
      return `**Code context:** ${descriptions.join('; ')}\n\n${response || ''}`;
    },
  };
})(window);
