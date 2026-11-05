(function(global) {
  const reviewAi = global.ReviewAi;
  const maxFileBytes = 64 * 1024;
  const fileType = 'reviewai-changed-file';
  const menuTitle = 'Changed file';
  const snapshots = new WeakMap();
  const views = new Set();

  function parseReference(reference) {
    const match = /^reviewai-file:([1-9]\d*):([0-9a-f]{40}):(\S+)$/.exec(reference || '');
    if (!match) return null;
    try {
      const path = decodeURIComponent(match[3]);
      if (!path || path.startsWith('/') || path.includes('\0') ||
          path.split('/').some(part => !part || part === '.' || part === '..')) return null;
      return {change: Number(match[1]), revision: match[2], path};
    } catch {
      return null;
    }
  }

  function toContextItem(reference) {
    const file = parseReference(reference);
    return file ? {
      type_id: fileType,
      identifier: reference,
      link: '',
      title: `${file.path} @ ${file.revision.slice(0, 8)}`,
      tooltip: `Whole file at revision ${file.revision}`,
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
          #status { margin-top: 8px; font-size: var(--font-size-small, 12px); }
        </style>
        <section id="picker" popover="auto" role="dialog" aria-labelledby="title">
          <h3 id="title">Attach code</h3>
          <label for="files">Changed file</label>
          <select id="files" aria-label="Changed file for code context" disabled></select>
          <div id="status" role="status"></div>
          <div id="actions">
            <button id="cancel" type="button">Cancel</button>
            <button id="attach" type="button" disabled>Attach file</button>
          </div>
        </section>`;
      this._popover = this.shadowRoot.querySelector('#picker');
      this._select = this.shadowRoot.querySelector('#files');
      this._attachButton = this.shadowRoot.querySelector('#attach');
      this._select.addEventListener('change', () => {
        this._attachButton.disabled = this._select.disabled || !this._select.value;
      });
      this._attachButton.addEventListener('click', () => this._attach());
      this.shadowRoot.querySelector('#cancel').addEventListener('click', () => this._close());
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
      const width = Math.min(360, global.innerWidth - 24);
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

    _attach() {
      if (this._attachButton.disabled || !this._input?.isConnected) return;
      const reference = `reviewai-file:${this._change._number}:${this._sha}:${encodeURIComponent(this._select.value)}`;
      const item = toContextItem(reference);
      if (!item) return;
      this._contextChips().forEach(chip => this._removeItem(chip.contextItem));
      this._input.dispatchEvent(new CustomEvent('context-item-added', {
        detail: item, bubbles: true, composed: true,
      }));
      this._close();
      this._anchor?.focus();
    }

    async _load() {
      const { _sha: sha, _key: key } = this;
      const changeNumber = this._change?._number;
      const generation = ++this._generation;
      this._select.replaceChildren(new Option('Choose a changed file…', ''));
      this._select.disabled = true;
      this._attachButton.disabled = true;
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
        this._attachButton.disabled = !this._select.value;
        status.textContent = count
          ? 'Whole text files up to 64 KiB. Deleted and binary files are unavailable.'
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
        regex: /reviewai-file:[1-9]\d*:[0-9a-f]{40}:\S+/g,
        placeholder: 'reviewai-file:change:revision:path',
        parse: toContextItem,
      }];
    },

    selected(change, request) {
      const items = (request?.external_contexts || [])
        .filter(item => item.type_id === fileType && !item.reviewai_consumed);
      if (!items.length) return null;
      if (items.length > 1) throw new Error('Attach one changed file per message. Remove the extra files.');
      const file = parseReference(items[0].identifier);
      if (!file || file.change !== change._number) {
        throw new Error('The attached file does not belong to this change. Remove it and select it again.');
      }
      const attachment = {revision: file.revision, path: file.path};
      snapshots.set(attachment, items[0]);
      return attachment;
    },

    consume(change, file) {
      const item = file && snapshots.get(file);
      if (!item) return;
      views.forEach(view => {
        if (view._change?._number === change._number) view._removeItem(item);
      });
    },

    describe(file, response) {
      if (!file) return response;
      const path = file.path.replace(/([\\`*_{}[\]()<>])/g, '\\$1');
      return `**Code context:** ${path} (${file.revision.slice(0, 8)})\n\n${response || ''}`;
    },
  };
})(window);
