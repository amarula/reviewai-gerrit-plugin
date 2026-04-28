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

})(window);
